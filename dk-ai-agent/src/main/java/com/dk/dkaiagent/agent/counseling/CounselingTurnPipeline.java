package com.dk.dkaiagent.agent.counseling;

import com.dk.dkaiagent.app.CounselingApp;
import com.dk.dkaiagent.app.CrisisResponse;
import com.dk.dkaiagent.app.SafetyOutputGuard;
import com.dk.dkaiagent.memory.RiskTier;
import com.dk.dkaiagent.memory.SafetyProperties;
import com.dk.dkaiagent.memory.SafetyTerms;
import com.dk.dkaiagent.history.ChatTurnService;
import com.dk.dkaiagent.memory.ConversationMemoryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 聊天轮次编排的唯一入口：快速与深度两条链路在此汇合。
 *
 * <p>职责收口为三件事：① 用户消息归档每轮恰好发生一次（幂等键在此透传）；② 按模式分流——
 * 深度走 {@link CounselingAgentExecutor} 的 plan→retrieve→grade→answer，快速走 Java RAG 链；
 * ③ 两条链路的事件统一为 {@link CounselingStreamEvent}，SSE 事件序列与 phase 名保持不变。</p>
 *
 * <p>分流后的作答一律使用 {@code *Prepared} 变体（轮次已归档），深度降级到标准链时
 * 因此不会把同一句话写两次。</p>
 */
@Component
public class CounselingTurnPipeline {

    private static final Logger auditLog = LoggerFactory.getLogger(CounselingTurnPipeline.class);

    /**
     * @param ownerId     会话归属用户 id，由控制器在开流前完成所有权校验后传入
     * @param clientMsgId 前端为本轮生成的幂等键，可空（旧客户端不携带时后端不去重）
     * @param deepThinking true 走深度链路，false 走标准 RAG 链路
     */
    public record CounselingTurnRequest(
            long ownerId,
            String chatId,
            String message,
            String clientMsgId,
            boolean deepThinking) {
    }

    @Resource
    private CounselingApp counselingApp;

    @Resource
    private CounselingAgentExecutor counselingAgentExecutor;

    @Resource
    private CrisisResponse crisisResponse;

    @Resource
    private SafetyProperties safetyProperties;

    @Resource
    private ChatTurnService chatTurnService;

    @Resource
    private ConversationMemoryService memoryService;

    @Autowired(required = false)
    private MeterRegistry metrics = new SimpleMeterRegistry();

    public Flux<CounselingStreamEvent> run(CounselingTurnRequest request) {
        // Admission runs before the HTTP stream starts, so conflicts retain HTTP 409 semantics.
        ChatTurnService.Turn turn = chatTurnService.begin(request.ownerId(), request.chatId(),
                request.clientMsgId(), request.message(), request.deepThinking());
        if (turn.replay()) {
            return Flux.just(CounselingStreamEvent.delta(turn.answer(), turn.mode(), turn.fallback()),
                    "COMPLETED".equals(turn.status()) ? CounselingStreamEvent.done(turn.mode(), turn.fallback())
                            : failure("partial", "上次回答已中断，已保存的部分如下。你可以发送新消息继续。"));
        }
        AtomicBoolean subscribed = new AtomicBoolean();
        return Flux.defer(() -> {
            if (!subscribed.compareAndSet(false, true)) {
                return Flux.error(new IllegalStateException("A turn stream can only be subscribed once"));
            }
            StringBuilder visible = new StringBuilder();
            AtomicBoolean terminal = new AtomicBoolean();
            AtomicReference<CounselingStreamEvent> last = new AtomicReference<>(
                    CounselingStreamEvent.done(turn.mode(), false));
            long started = System.nanoTime();
            Flux<CounselingStreamEvent> source = Flux.defer(() -> {
            // 危机拦截先于一切：IMMINENT 级（手段/计划/进行中）直接走专用模板，
            // 不进入任何 LLM 聊天链——这是快速模式此前缺失的危机前置检查，
            // 现在两条模式在 pipeline 收口处统一获得。
            RiskTier tier = SafetyTerms.assess(request.message());
            if (tier.isSafetyRelevant()) {
                // A2 审计：只记分级/会话/主体/模式，绝不记正文（隐私红线）。
                // IMMINENT=WARN 便于日志告警直接筛出；PASSIVE=INFO 留痕不告警。
                if (tier == RiskTier.IMMINENT) {
                    auditLog.warn("安全事件 tier=IMMINENT chatId={} ownerId={} deep={}",
                            request.chatId(), request.ownerId(), request.deepThinking());
                } else {
                    auditLog.info("安全事件 tier=PASSIVE chatId={} ownerId={} deep={}",
                            request.chatId(), request.ownerId(), request.deepThinking());
                }
            }
            counselingApp.prepareConversationTurn(
                    request.ownerId(), request.chatId(), request.message(), turn.key());
            if (tier == RiskTier.IMMINENT && safetyProperties.isCrisisResponseEnabled()) {
                return crisisResponse(request);
            }
            Flux<CounselingStreamEvent> events = request.deepThinking()
                    ? counselingAgentExecutor.prepareAndAnswer(
                            request.message(), request.chatId(), request.ownerId())
                    : standardAnswer(request.ownerId(), request.message(), request.chatId());
            // 高风险轮次先检查完整输出，再向客户端发送通过检查的文本。
            return SafetyOutputGuard.guard(events, tier, crisisResponse.supplement());
            });
            // Hard deadline, including continuously arriving tokens; timeout() alone only bounds idle time.
            return source.takeUntilOther(Mono.delay(Duration.ofSeconds(180))
                            .flatMap(ignored -> Mono.error(new IllegalStateException("turn deadline exceeded"))))
                    .concatMap(event -> {
                        last.set(event);
                        if ("delta".equals(event.type())) {
                            if (visible.length() + event.content().length() > 32_000) throw new IllegalStateException("answer budget exceeded");
                            visible.append(event.content());
                        }
                        if ("done".equals(event.type())) {
                            if (visible.toString().isBlank()) throw new IllegalStateException("empty answer");
                            chatTurnService.finish(request.ownerId(), request.chatId(), turn.key(),
                                    visible.toString(), "COMPLETED", event.effectiveMode(), event.fallback());
                            terminal.set(true);
                            afterCommit(request.chatId());
                            metrics.counter("counseling.turns", "outcome", "completed").increment();
                        }
                        return Mono.just(event);
                    })
                    .takeUntil(event -> "done".equals(event.type()))
                    .concatWith(Flux.defer(() -> terminal.get() ? Flux.empty()
                            : Flux.error(new IllegalStateException("missing completion event"))))
                    .onErrorResume(error -> {
                        boolean stored = finishInterrupted(request, turn, visible.toString(), "FAILED", last.get(), terminal);
                        auditLog.warn("Turn failed; chatId={} errorType={}", request.chatId(), error.getClass().getSimpleName());
                        return Flux.just(failure(stored && !visible.isEmpty() ? "partial" : "failed",
                                stored ? "回答未完成，已生成的内容已保存。" : "回答未能保存，请稍后重试。"));
                    })
                    .doFinally(signal -> {
                        if (signal == reactor.core.publisher.SignalType.CANCEL) {
                            finishInterrupted(request, turn, visible.toString(), "CANCELLED", last.get(), terminal);
                        }
                        metrics.timer("counseling.turn.duration", "mode", turn.mode())
                                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
                    });
        });
    }

    private void afterCommit(String chatId) {
        // The answer is already durable. Optional memory maintenance must not undo HTTP success.
        try {
            counselingApp.clearConversationMemory(chatId);
            memoryService.onTurnArchived(chatId);
        } catch (RuntimeException error) {
            auditLog.warn("Post-commit memory maintenance failed; chatId={} errorType={}",
                    chatId, error.getClass().getSimpleName());
        }
    }

    private boolean finishInterrupted(CounselingTurnRequest request, ChatTurnService.Turn turn, String answer,
                                      String status, CounselingStreamEvent last, AtomicBoolean terminal) {
        if (!terminal.compareAndSet(false, true)) return true;
        try {
            chatTurnService.finish(request.ownerId(), request.chatId(), turn.key(), answer, status,
                    last.effectiveMode(), last.fallback());
            return true;
        } catch (RuntimeException error) {
            metrics.counter("counseling.archive.failures").increment();
            auditLog.error("Unable to save interrupted turn; chatId={} errorType={}", request.chatId(), error.getClass().getSimpleName());
            return false;
        } finally {
            counselingApp.clearConversationMemory(request.chatId());
            metrics.counter("counseling.turns", "outcome", status.toLowerCase(java.util.Locale.ROOT)).increment();
        }
    }

    private static CounselingStreamEvent failure(String phase, String message) {
        return new CounselingStreamEvent("error", message, phase, "standard", false);
    }

    /** 危机模板响应：用户消息已归档，模板流式发出，回答照常归档并触发记忆整合。 */
    private Flux<CounselingStreamEvent> crisisResponse(CounselingTurnRequest request) {
        String text = crisisResponse.render();
        return Flux.fromIterable(crisisResponse.toChunks(text))
                .map(chunk -> CounselingStreamEvent.delta(chunk, "standard", false))
                .concatWithValues(CounselingStreamEvent.done("standard", false));
    }

    /** 标准 RAG 作答（轮次已归档）。delta/done 的字段值与历史 SSE 契约逐字段一致。 */
    private Flux<CounselingStreamEvent> standardAnswer(long ownerId, String message, String chatId) {
        return counselingApp.doChatWithRagByStreamPrepared(ownerId, message, chatId)
                .map(chunk -> "[DONE]".equals(chunk)
                        ? CounselingStreamEvent.done("standard", false)
                        : CounselingStreamEvent.delta(chunk, "standard", false));
    }
}
