package com.dk.dkaiagent.advisor;

import com.dk.dkaiagent.orchestration.ExecutionContextScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/**
 * AI 调用边界日志。心理咨询内容属于敏感数据，因此这里只记录调用状态，
 * 不记录提示词、模型回复或工具参数。
 */
@Slf4j
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {

	@Override
	public String getName() {
		return this.getClass().getSimpleName();
	}

	@Override
	public int getOrder() {
		return 0;
	}

	private ChatClientRequest before(ChatClientRequest request) {
		log.debug("AI request started; requestId={}", currentRequestId());
		return request;
	}

	private void observeAfter(ChatClientResponse chatClientResponse) {
		log.debug("AI request completed; requestId={}", currentRequestId());
		var response = chatClientResponse.chatResponse();
		if (response == null || response.getMetadata().getUsage() == null) return;
		var usage = response.getMetadata().getUsage();
		// Aggregated counters contain no prompts, answers, conversation IDs or user labels.
		if (usage.getPromptTokens() != null && usage.getPromptTokens() > 0)
			io.micrometer.core.instrument.Metrics.counter("counseling.model.tokens", "kind", "input").increment(usage.getPromptTokens());
		if (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0)
			io.micrometer.core.instrument.Metrics.counter("counseling.model.tokens", "kind", "output").increment(usage.getCompletionTokens());
	}

	private static String currentRequestId() {
		return ExecutionContextScope.current()
				.map(context -> context.requestId())
				.orElse("unscoped");
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain chain) {
		chatClientRequest = before(chatClientRequest);
		ChatClientResponse chatClientResponse = chain.nextCall(chatClientRequest);
		observeAfter(chatClientResponse);
		return chatClientResponse;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain chain) {
		chatClientRequest = before(chatClientRequest);
		Flux<ChatClientResponse> chatClientResponseFlux = chain.nextStream(chatClientRequest);
		return (new ChatClientMessageAggregator()).aggregateChatClientResponse(chatClientResponseFlux, this::observeAfter);
	}
}
