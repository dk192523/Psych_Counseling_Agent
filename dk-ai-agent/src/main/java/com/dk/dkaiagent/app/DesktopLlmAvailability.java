package com.dk.dkaiagent.app;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Only the local desktop may start without cloud credentials. */
@Component
public class DesktopLlmAvailability {

    public static final String UNCONFIGURED_MESSAGE =
            "本地客户端尚未配置 DeepSeek API Key，AI 对话和联网搜索暂不可用。请配置密钥后重启客户端。";

    private final boolean unconfigured;

    public DesktopLlmAvailability(Environment environment) {
        this.unconfigured = environment.acceptsProfiles(Profiles.of("local-desktop"))
                && !environment.getProperty("app.desktop.llm-configured", Boolean.class, false);
    }

    public boolean isUnconfigured() {
        return unconfigured;
    }
}
