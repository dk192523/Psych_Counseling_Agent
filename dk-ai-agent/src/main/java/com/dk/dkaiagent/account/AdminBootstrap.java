package com.dk.dkaiagent.account;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 初始超管引导（冻结合约 AUTH-v1）：无任何 ADMIN 时创建 admin。
 * 口令必须取 env ADMIN_INITIAL_PASSWORD；无管理员且未配置时阻止启动。
 * BCrypt 落库，日志仅记录创建成功及口令来源，绝不输出口令或哈希。
 * 无主历史会话保持 owner_id IS NULL；启动仅记录数量，归属必须由人工明确映射。
 *
 * 时机：@PostConstruct 处于 refresh() 的 finishBeanFactoryInitialization 阶段——此时依赖注入
 * 保证 UserRepository 的 @PostConstruct 建表 DDL 已完成，而端口绑定发生在随后的 finishRefresh →
 * lifecycleProcessor.onRefresh() → WebServerStartStopLifecycle。即引导在 Tomcat 接受任何流量之前
 * 完成，首启抢注竞态窗口归零（原 ApplicationReadyEvent 排在嵌入 ApplicationRunner 之后，窗口长达
 * 整个 ONNX 嵌入耗时）。与注册侧的 admin 保留字拒绝（UserAccountService.register）构成纵深防御。
 */
@Component
@Slf4j
public class AdminBootstrap {

    private static final String ADMIN_USERNAME = "admin";

    private final UserRepository userRepository;
    private final UserAccountService userAccountService;
    private final PasswordEncoder passwordEncoder;
    private final String configuredInitialPassword;

    public AdminBootstrap(UserRepository userRepository,
                          UserAccountService userAccountService,
                          PasswordEncoder passwordEncoder,
                          @Value("${app.admin.initial-password:}") String configuredInitialPassword) {
        this.userRepository = userRepository;
        this.userAccountService = userAccountService;
        this.passwordEncoder = passwordEncoder;
        this.configuredInitialPassword = configuredInitialPassword;
    }

    @PostConstruct
    void ensureInitialAdmin() {
        reportOrphanConversations();
        if (userRepository.countByRole(UserAccountService.ROLE_ADMIN) > 0) {
            return;
        }
        if (configuredInitialPassword == null || configuredInitialPassword.isBlank()) {
            throw new IllegalStateException("尚无管理员，首次启动必须配置 ADMIN_INITIAL_PASSWORD；未创建初始超管");
        }
        String initialPassword = configuredInitialPassword;
        // env 配置口令与注册/改密走同一事实源校验（≥8 位、UTF-8 ≤72 字节）：BCrypt 对超 72 字节
        // 静默截断，必须在入口显式拒绝。校验失败在启动期抛出（fail-closed），异常消息不含口令本身。
        AuthValidation.validatePassword(initialPassword);
        try {
            userRepository.insertUser(ADMIN_USERNAME,
                    passwordEncoder.encode(initialPassword), UserAccountService.ROLE_ADMIN);
        } catch (DataIntegrityViolationException usernameCollision) {
            // 用户名冲突：回查冲突行裁决语义。仅当冲突行确为 ADMIN（多实例并发首启，另一实例已引导）
            // 才按"已引导"处理；若 "admin" 被非管理员账号占用（如首启窗口内抢注），记 ERROR 要求人工
            // 介入并直接返回，不更改现有账号或任何会话归属。
            PsychUser existing = userRepository.findByUsername(ADMIN_USERNAME).orElse(null);
            if (existing != null && UserAccountService.ROLE_ADMIN.equals(existing.role())) {
                return;
            }
            log.error("初始超管创建失败：保留用户名被非管理员账号占用，需人工介入");
            return;
        }
        log.info("初始超管已创建，初始口令来源 ADMIN_INITIAL_PASSWORD");
    }

    private void reportOrphanConversations() {
        long orphanCount = userRepository.countOrphanConversations();
        if (orphanCount > 0) {
            log.warn("检测到 {} 个无主历史会话，保留无主状态；迁移需人工明确映射", orphanCount);
        }
    }
}
