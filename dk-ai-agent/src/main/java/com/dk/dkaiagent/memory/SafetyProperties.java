package com.dk.dkaiagent.memory;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全分层配置：危机响应模板的资源表与总开关。
 * 资源必须可配置——热线号码随地区/时间变化，硬编码在代码里既不诚实也无法本地化；
 * 文案层面始终附带"以当地公布为准"，配置只提供默认值。
 */
@Component
@ConfigurationProperties(prefix = "app.safety")
public class SafetyProperties {

    /** 总开关：关闭后 IMMINENT 也不再走专用危机模板（仅供开发调试，生产必须为 true）。 */
    private boolean crisisResponseEnabled = true;

    /** 危机响应模板中展示的求助资源，按展示顺序。 */
    private List<String> hotlines = new ArrayList<>(List.of(
            "全国心理援助热线 12356（24 小时）",
            "北京心理危机研究与干预中心 010-82951332",
            "急救 120 · 报警 110"
    ));

    @PostConstruct
    void validate() {
        if (hotlines == null || hotlines.isEmpty()) {
            // 空资源表会让危机模板失去可执行的建议——宁可启动失败也不能静默降级。
            throw new IllegalArgumentException("app.safety.hotlines must contain at least one resource");
        }
    }

    public boolean isCrisisResponseEnabled() {
        return crisisResponseEnabled;
    }

    public void setCrisisResponseEnabled(boolean crisisResponseEnabled) {
        this.crisisResponseEnabled = crisisResponseEnabled;
    }

    public List<String> getHotlines() {
        return hotlines;
    }

    public void setHotlines(List<String> hotlines) {
        this.hotlines = hotlines;
    }
}
