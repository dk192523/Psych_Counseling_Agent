package com.dk.dkaiagent.account;

/**
 * 会话踢出端口：停用账号或凭据轮换（改密/管理端重置临时密码）时立即销毁其全部 HttpSession（冻结合约 AUTH-v1）。
 * B1 只定义接口；Wave2 的 security/ActiveSessionService 基于 Spring Security SessionRegistry 实现。
 * UserAccountService 以 Optional 注入，缺实现时也能编译运行（仅停用/轮换后旧会话自然过期）。
 */
public interface SessionKillPort {

    void killSessions(long userId);
}
