package com.dk.psychclient;

import java.util.Set;

/** Deliberately mirrors controller contracts; no prefix-based forwarding. */
final class ClientApiRoutes {
    private static final Set<String> GET = Set.of("/api/auth/csrf", "/api/auth/me",
            "/api/system/health", "/api/health", "/api/ai/conversations",
            "/api/exam/questions", "/api/admin/users", "/api/admin/stats");
    private static final Set<String> POST = Set.of("/api/auth/login", "/api/auth/register",
            "/api/auth/logout", "/api/users/me/password", "/api/ai/conversations",
            "/api/ai/counseling/chat/sync", "/api/ai/counseling/chat/sse", "/api/admin/users/bulk");

    private ClientApiRoutes() { }

    static boolean allows(String method, String path) {
        return switch (method) {
            case "GET" -> GET.contains(path) || path.matches("/api/ai/conversations/[A-Za-z0-9_-]{1,64}");
            case "POST" -> POST.contains(path)
                    || path.matches("/api/exam/questions/[A-Za-z0-9_-]{1,128}/answer")
                    || path.matches("/api/admin/users/[1-9][0-9]{0,18}/(disable|enable|password-reset)");
            case "DELETE" -> path.matches("/api/ai/conversations/[A-Za-z0-9_-]{1,64}")
                    || path.matches("/api/admin/users/[1-9][0-9]{0,18}");
            default -> false;
        };
    }
}
