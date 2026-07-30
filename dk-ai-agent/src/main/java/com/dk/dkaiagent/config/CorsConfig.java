package com.dk.dkaiagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局跨域配置（MVC CORS：SecurityConfig 的 .cors(withDefaults()) 在无 CorsConfigurationSource Bean
 * 时回退到本 WebMvcConfigurer 配置，Security 与 MVC 共用同一策略）。
 *
 * 安全红线：allowCredentials(true) 下严禁在 allowedOriginPatterns 中出现 "*"——通配模式会把任意
 * 请求 Origin 原样回显到 Access-Control-Allow-Origin 并附 Access-Control-Allow-Credentials: true，
 * 等于把会话 Cookie 的跨域读权限交给浏览器 SameSite 默认值（旧内核浏览器按 None 处理即完全跨域可读，
 * 同站兄弟子域/XSS 子站在现代浏览器下同样可读）。
 *
 * 策略：环境变量驱动的显式白名单。缺省仅放行开发前端（localhost / 127.0.0.1 的 3001 端口）；
 * 生产经 nginx 同源反代，不需要任何跨域放行——置空（compose 缺省即空）即不注册 CORS 映射。
 * 若生产前后端确实分域，经 APP_CORS_ALLOWED_ORIGIN_PATTERNS 显式列出生产前端 Origin，仍不得含 "*"。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origin-patterns:http://localhost:3001,http://127.0.0.1:3001}")
    private String[] allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> patterns = new ArrayList<>();
        if (allowedOriginPatterns != null) {
            for (String pattern : allowedOriginPatterns) {
                if (pattern != null && !pattern.isBlank()) {
                    patterns.add(pattern.trim());
                }
            }
        }
        if (patterns.isEmpty()) {
            // 生产/缺省同源部署：不注册任何 CORS 放行，跨域请求直接被浏览器同源策略挡下。
            return;
        }
        registry.addMapping("/**")
                // 允许发送 Cookie（会话鉴权依赖跨域携带 Cookie）；patterns 为显式白名单，
                // 仅匹配的 Origin 被回显 ACAO + ACAC，其余 Origin 不获得任何 CORS 头。
                .allowCredentials(true)
                .allowedOriginPatterns(patterns.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
        // 刻意不设 exposedHeaders("*")：凭据模式下 "*" 按规范被当字面量处理，且无实际暴露需求。
    }
}
