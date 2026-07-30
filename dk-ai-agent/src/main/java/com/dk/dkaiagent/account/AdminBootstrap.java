package com.dk.dkaiagent.account;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 初始超管引导（冻结合约 AUTH-v1）：无任何 ADMIN 时创建 admin。
 * 口令取 env ADMIN_INITIAL_PASSWORD；未设置则 SecureRandom 生成 12 位字母数字，
 * BCrypt 落库并 WARN 日志输出一次。口令绝不写入文件/异常/接口返回。
 * 随后把无主历史会话归属到超管（幂等，每次启动只影响 owner_id IS NULL 的行）。
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
    private static final int GENERATED_PASSWORD_LENGTH = 12;

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
        if (userRepository.countByRole(UserAccountService.ROLE_ADMIN) > 0) {
            adoptOrphanConversations();
            return;
        }
        String initialPassword =
                configuredInitialPassword == null || configuredInitialPassword.isBlank()
                        ? userAccountService.generateRandomPassword(GENERATED_PASSWORD_LENGTH)
                        : configuredInitialPassword;
        // env 配置口令与注册/改密走同一事实源校验（≥8 位、UTF-8 ≤72 字节）：BCrypt 对超 72 字节
        // 静默截断，必须在入口显式拒绝。校验失败在启动期抛出（fail-closed），异常消息不含口令本身；
        // 生成的 12 位字母数字口令恒合规（12 字节，天然满足双边界），不会因此阻断。
        AuthValidation.validatePassword(initialPassword);
        try {
            userRepository.insertUser(ADMIN_USERNAME,
                    passwordEncoder.encode(initialPassword), UserAccountService.ROLE_ADMIN);
        } catch (DataIntegrityViolationException usernameCollision) {
            // 用户名冲突：回查冲突行裁决语义。仅当冲突行确为 ADMIN（多实例并发首启，另一实例已引导）
            // 才按"已引导"处理；若 "admin" 被非管理员账号占用（如首启窗口内抢注），记 ERROR 要求人工
            // 介入并直接返回——绝不能把无主历史会话 adopt 到非 ADMIN 账号。
            PsychUser existing = userRepository.findByUsername(ADMIN_USERNAME).orElse(null);
            if (existing != null && UserAccountService.ROLE_ADMIN.equals(existing.role())) {
                adoptOrphanConversations();
                return;
            }
            log.error("用户名 {} 被非管理员账号占用，初始超管创建失败，需人工介入（清理占用账号或将现有管理员提权后重启）",
                    ADMIN_USERNAME);
            return;
        }
        log.warn("初始超管已创建，用户名 {}，密码 {}（仅显示一次，请尽快修改）", ADMIN_USERNAME, initialPassword);
        adoptOrphanConversations();
    }

    private void adoptOrphanConversations() {
        userRepository.findByUsername(ADMIN_USERNAME).ifPresent(admin -> {
            // 归属断言：无主历史咨询会话只允许落到真正的 ADMIN，杜绝被非管理员账号窃取。
            if (!UserAccountService.ROLE_ADMIN.equals(admin.role())) {
                log.error("用户名 {} 非 ADMIN 账号，跳过无主会话归属，需人工介入", ADMIN_USERNAME);
                return;
            }
            int adopted = userRepository.adoptOrphanConversations(admin.id());
            if (adopted > 0) {
                log.info("已将 {} 个无主历史会话归属到管理员 id={}", adopted, admin.id());
            }
        });
    }
}
