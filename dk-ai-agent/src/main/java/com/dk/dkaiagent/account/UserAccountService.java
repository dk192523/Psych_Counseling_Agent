package com.dk.dkaiagent.account;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 账号域用例（冻结合约 AUTH-v1）：注册/登录认证、改密、管理端启停/重置/统计。
 * 凭据错误与锁定返回同一泛化文案不枚举账号存在性；仅 DISABLED 返回可识别错误码。
 */
@Service
public class UserAccountService {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    public static final String GENERIC_AUTH_ERROR_MESSAGE = "用户名或密码错误";
    public static final String DISABLED_MESSAGE = "该账号已被停用";

    private static final int TEMP_PASSWORD_LENGTH = 12;
    private static final int MAX_BULK_USERS = 100;
    private static final int MAX_PAGE_SIZE = 100;
    /** 初始超管保留用户名：注册入口拒绝（大小写不敏感），结构性封堵首启窗口内的抢注攻击面。 */
    private static final String RESERVED_ADMIN_USERNAME = "admin";
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final UserRepository userRepository;
    private final LoginAttemptService loginAttemptService;
    private final PasswordEncoder passwordEncoder;
    private final Optional<SessionKillPort> sessionKillPort;
    private final SecureRandom secureRandom = new SecureRandom();
    // 不存在的账号比对哑哈希拉平时序，避免登录响应时间泄露账号是否存在。
    private final String dummyPasswordHash;

    public UserAccountService(UserRepository userRepository,
                              LoginAttemptService loginAttemptService,
                              PasswordEncoder passwordEncoder,
                              Optional<SessionKillPort> sessionKillPort) {
        this.userRepository = userRepository;
        this.loginAttemptService = loginAttemptService;
        this.passwordEncoder = passwordEncoder;
        this.sessionKillPort = sessionKillPort;
        this.dummyPasswordHash = passwordEncoder.encode("timing-equalization-dummy");
    }

    public PsychUser register(String rawUsername, String rawPassword) {
        String username = AuthValidation.normalizeUsername(rawUsername);
        AuthValidation.validateUsername(username);
        AuthValidation.validatePassword(rawPassword);
        // 保留字拒绝与计时无关地关闭"首启抢注 admin"劫持面（AdminBootstrap 的超管用户名恒为 admin）。
        if (RESERVED_ADMIN_USERNAME.equalsIgnoreCase(username)) {
            throw new AuthValidation.ValidationException("用户名为系统保留字");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            // 重名命中后补一次哑 BCrypt encode，拉平与 201 路径（含真实 encode）的耗时差，
            // 消除以响应时间区分账号是否存在的时序旁路；主枚举面由注册限流兜住。
            passwordEncoder.encode("timing-equalization-duplicate");
            throw new DuplicateUsernameException();
        }
        long id;
        try {
            id = userRepository.insertUser(username, passwordEncoder.encode(rawPassword), ROLE_USER);
        } catch (DataIntegrityViolationException raceDuplicate) {
            // 预检放行但唯一约束在并发下命中：与查重同样 409，不外泄 500。
            throw new DuplicateUsernameException();
        }
        return userRepository.findById(id).orElseThrow();
    }

    public AuthResult authenticate(String rawUsername, String rawPassword) {
        String username = AuthValidation.normalizeUsername(rawUsername);
        // 原子准入：单次 compute 内完成锁定判定并预扣一个 BCrypt 比对名额，
        // 并发爆发波次每窗口放行的真实比对严格 ≤ LOCK_THRESHOLD（修复"先查后记"的检查-执行竞态）。
        if (!loginAttemptService.tryBeginCheck(username)) {
            return AuthResult.failure(AuthFailure.LOCKED, GENERIC_AUTH_ERROR_MESSAGE);
        }
        boolean quotaSettled = false;
        try {
            Optional<PsychUser> candidate = userRepository.findByUsername(username);
            String storedHash = candidate.map(PsychUser::passwordHash).orElse(dummyPasswordHash);
            boolean passwordMatches = rawPassword != null && passwordEncoder.matches(rawPassword, storedHash);
            if (candidate.isEmpty() || !passwordMatches) {
                loginAttemptService.recordFailure(username);
                quotaSettled = true;
                return AuthResult.failure(AuthFailure.BAD_CREDENTIALS, GENERIC_AUTH_ERROR_MESSAGE);
            }
            PsychUser user = candidate.get();
            if (STATUS_DISABLED.equals(user.status())) {
                // DISABLED 属凭据正确后的状态判定：不计失败，但必须归还预扣名额，否则泄漏至窗口过期。
                loginAttemptService.releaseInFlight(username);
                quotaSettled = true;
                return AuthResult.failure(AuthFailure.DISABLED, DISABLED_MESSAGE);
            }
            loginAttemptService.recordSuccess(username);
            quotaSettled = true;
            userRepository.updateLastLogin(user.id());
            return AuthResult.success(user);
        } finally {
            // 比对期间抛异常（如 DB 故障）时兜底归还名额。
            if (!quotaSettled) {
                loginAttemptService.releaseInFlight(username);
            }
        }
    }

    /**
     * 轻量状态查询：登录会话登记后即时复核用，闭合"停用与登录并发"的竞态窗口。
     * 行不存在收敛为 DISABLED（fail-closed）。
     */
    public String statusOf(long userId) {
        return userRepository.findById(userId).map(PsychUser::status).orElse(STATUS_DISABLED);
    }

    public void changeOwnPassword(long userId, String oldPassword, String newPassword) {
        AuthValidation.validatePassword(newPassword);
        PsychUser user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, user.passwordHash())) {
            throw new BadOldPasswordException();
        }
        userRepository.updatePasswordHash(userId, passwordEncoder.encode(newPassword));
        // 凭据轮换即吊销全部旧会话（含调用者自身会话，前端按 401 拦截重登）：
        // 窃取 Cookie/XSS 持有会话的攻击者在密码被换后立即失效，kill 置于 DB 更新之后，失败不影响轮换。
        sessionKillPort.ifPresent(killer -> killer.killSessions(userId));
    }

    /** 生成并落库 12 位随机临时密码；明文仅返回一次，调用方负责一次性展示。 */
    public String adminResetPassword(long actorId, long targetUserId) {
        // 与启停路径同形：管理员不得经本端点重置自身密码——否则绕过 changeOwnPassword 的旧密码证明，
        // 且 killSessions 会连带吊销管理员自身会话，会话窃取者凭返回的明文临时密码即可顶替登录、
        // 把合法管理员永久锁死。self 检查以服务层为准（控制器不重复判断，与 adminSetStatus 一致）。
        if (actorId == targetUserId) {
            throw new SelfOperationException();
        }
        userRepository.findById(targetUserId).orElseThrow(UserNotFoundException::new);
        String tempPassword = generateRandomPassword(TEMP_PASSWORD_LENGTH);
        userRepository.updatePasswordHash(targetUserId, passwordEncoder.encode(tempPassword));
        // 与停用路径一致：重置临时密码后立即踢掉目标用户全部会话（self 已在上方拒绝，管理员自身会话必不受影响）。
        sessionKillPort.ifPresent(killer -> killer.killSessions(targetUserId));
        return tempPassword;
    }

    public void adminSetStatus(long actorId, long targetUserId, boolean disable, String reason) {
        if (actorId == targetUserId) {
            throw new SelfOperationException();
        }
        userRepository.findById(targetUserId).orElseThrow(UserNotFoundException::new);
        userRepository.updateStatus(targetUserId, disable ? STATUS_DISABLED : STATUS_ACTIVE,
                disable ? reason : null);
        if (disable) {
            sessionKillPort.ifPresent(killer -> killer.killSessions(targetUserId));
        }
    }

    public BulkResult bulkSetStatus(long actorId, List<Long> userIds, boolean disable, String reason) {
        if (userIds == null || userIds.isEmpty()) {
            return new BulkResult(List.of(), List.of());
        }
        if (userIds.size() > MAX_BULK_USERS) {
            throw new AuthValidation.ValidationException(
                    "批量操作一次最多处理 " + MAX_BULK_USERS + " 个用户");
        }
        List<Long> succeeded = new ArrayList<>();
        List<BulkFailure> failed = new ArrayList<>();
        for (Long userId : userIds) {
            if (userId == null) {
                failed.add(new BulkFailure(-1L, "INVALID_ID"));
                continue;
            }
            if (userId.longValue() == actorId) {
                failed.add(new BulkFailure(userId, "SELF_OPERATION"));
                continue;
            }
            try {
                if (userRepository.findById(userId).isEmpty()) {
                    failed.add(new BulkFailure(userId, "NOT_FOUND"));
                    continue;
                }
                userRepository.updateStatus(userId, disable ? STATUS_DISABLED : STATUS_ACTIVE,
                        disable ? reason : null);
                if (disable) {
                    sessionKillPort.ifPresent(killer -> killer.killSessions(userId));
                }
                succeeded.add(userId);
            } catch (RuntimeException singleFailure) {
                // 单项失败不中断批量：记入 failed 继续下一项。
                failed.add(new BulkFailure(userId, "INTERNAL_ERROR"));
            }
        }
        return new BulkResult(List.copyOf(succeeded), List.copyOf(failed));
    }

    public AdminStats adminStats() {
        return new AdminStats(
                userRepository.countAll(),
                userRepository.countByRole(ROLE_ADMIN),
                userRepository.countByStatus(STATUS_ACTIVE),
                userRepository.countByStatus(STATUS_DISABLED),
                userRepository.countConversations(),
                userRepository.countMessages());
    }

    public UserPage listUsers(String keyword, String status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        List<UserRepository.UserListRow> content =
                userRepository.listUsers(keyword, status, safePage, safeSize);
        return new UserPage(content, userRepository.countUsers(keyword, status), safePage, safeSize);
    }

    public String generateRandomPassword(int length) {
        StringBuilder password = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            password.append(PASSWORD_ALPHABET.charAt(secureRandom.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    public enum AuthFailure {
        BAD_CREDENTIALS, DISABLED, LOCKED
    }

    public record AuthResult(boolean ok, AuthFailure failure, PsychUser user, String message) {
        static AuthResult success(PsychUser user) {
            return new AuthResult(true, null, user, null);
        }

        static AuthResult failure(AuthFailure failure, String message) {
            return new AuthResult(false, failure, null, message);
        }
    }

    public record AdminStats(
            long totalUsers,
            long adminCount,
            long activeUsers,
            long disabledUsers,
            long totalConversations,
            long totalMessages
    ) {
    }

    public record BulkFailure(long id, String error) {
    }

    public record BulkResult(List<Long> succeeded, List<BulkFailure> failed) {
    }

    public record UserPage(List<UserRepository.UserListRow> content, long totalElements, int page, int size) {
    }

    public static class DuplicateUsernameException extends RuntimeException {
        public DuplicateUsernameException() {
            super("DUPLICATE_USERNAME");
        }
    }

    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException() {
            super("USER_NOT_FOUND");
        }
    }

    public static class SelfOperationException extends RuntimeException {
        public SelfOperationException() {
            super("SELF_OPERATION");
        }
    }

    public static class BadOldPasswordException extends RuntimeException {
        public BadOldPasswordException() {
            super("BAD_OLD_PASSWORD");
        }
    }
}
