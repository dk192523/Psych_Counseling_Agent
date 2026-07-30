package com.dk.dkaiagent.agent.counseling;

/**
 * Stable SSE payload shared by the current Spring AI agent and future agent providers.
 * Only observable execution stages are exposed; hidden model reasoning is never sent.
 */
public record CounselingStreamEvent(
        String type,
        String content,
        String phase,
        String effectiveMode,
        boolean fallback) {

    public static CounselingStreamEvent status(String phase, String content) {
        return new CounselingStreamEvent("status", content, phase, "deep", false);
    }

    public static CounselingStreamEvent fallback(String phase, String content) {
        return new CounselingStreamEvent("fallback", content, phase, "standard", true);
    }

    public static CounselingStreamEvent delta(String content, String effectiveMode, boolean fallback) {
        return new CounselingStreamEvent("delta", content, null, effectiveMode, fallback);
    }

    public static CounselingStreamEvent done(String effectiveMode, boolean fallback) {
        return new CounselingStreamEvent("done", "", null, effectiveMode, fallback);
    }
}
