package com.dk.dkaiagent.security;

import com.dk.dkaiagent.account.SessionKillPort;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 活跃会话登记与即时吊销（冻结合约 AUTH-v1）。
 * SessionRegistry 只索引 sessionId→principal，无法触达真实 HttpSession，故另持 sessionId→HttpSession 活引用表，
 * killSessions 时先 expireNow() 标记注册表、再 invalidate() 真实会话，停用即时生效。
 * 活引用表由本类的 HttpSessionListener 回调（Boot 自动把 Bean 注册为容器监听器）在会话销毁时清理。
 * 进程内有效：多副本部署下吊销只影响本实例，与 LoginAttemptService 的权衡一致。
 */
@Service
public class ActiveSessionService implements SessionKillPort, HttpSessionListener {

    private final SessionRegistry sessionRegistry;
    private final ConcurrentMap<String, HttpSession> liveSessions = new ConcurrentHashMap<>();

    public ActiveSessionService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /** 登录成功后由 AuthController 调用：把会话按用户 id 索引进注册表并留下活引用。 */
    public void registerLogin(long userId, HttpSession session) {
        SessionPrincipal principal = new SessionPrincipal(userId);
        // 同一 sessionId 重复登记（极端竞态）时先摘除旧索引，registerNewSession 对重复 id 会抛异常。
        if (sessionRegistry.getSessionInformation(session.getId()) != null) {
            sessionRegistry.removeSessionInformation(session.getId());
        }
        sessionRegistry.registerNewSession(session.getId(), principal);
        liveSessions.put(session.getId(), session);
    }

    /** 枚举该用户全部会话：注册表标记过期 + 真实会话立即销毁（跨线程 invalidate 对 Tomcat 安全）。 */
    @Override
    public void killSessions(long userId) {
        List<Object> principals = sessionRegistry.getAllPrincipals().stream()
                .filter(principal -> principal instanceof SessionPrincipal key && key.userId() == userId)
                .toList();
        for (Object principal : principals) {
            for (SessionInformation information : sessionRegistry.getAllSessions(principal, false)) {
                information.expireNow();
                HttpSession session = liveSessions.remove(information.getSessionId());
                if (session != null) {
                    try {
                        session.invalidate();
                    } catch (IllegalStateException alreadyInvalidated) {
                        // 会话已自然过期或已在别处销毁，忽略即安全。
                    }
                }
            }
        }
    }

    @Override
    public void sessionCreated(HttpSessionEvent se) {
        // 匿名会话不登记；仅在登录成功时经 registerLogin 索引。
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        liveSessions.remove(se.getSession().getId());
    }

    /** 注册表检索键：record 值语义保证按 userId 等值匹配 getAllSessions。 */
    record SessionPrincipal(long userId) {
    }
}
