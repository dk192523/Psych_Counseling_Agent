package com.dk.dkaiagent.security;

import com.dk.dkaiagent.account.UserAccountService;
import com.dk.dkaiagent.security.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * 安全主配置（冻结合约 AUTH-v1）。
 * 认证采用 HttpSession + Cookie 有状态会话：登录由 AuthController 手工完成（UserAccountService 校验凭据），
 * principal 为 {@link PsychUserPrincipal}（擦除密码字段）。/api 前缀由 context-path 承载，
 * 故下方匹配模式均为 servlet 相对路径。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final ObjectMapper ERROR_BODY_MAPPER = new ObjectMapper();

    /**
     * API 文档放行开关：application.yml 缺省 true（开发联调可见），application-prod.yml 强制 false。
     * 关闭后四个文档路径不再 permitAll，落入 anyRequest().authenticated()——即便有人重新启用
     * springdoc/knife4j 端点，安全链仍拦截未认证访问（与端点禁用构成纵深防御）。
     */
    @Value("${app.docs.enabled:true}")
    private boolean docsEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF：冻结合约 AUTH-v1 显式关闭。
                // 权衡：主咨询流已迁移为 POST + fetch SSE，但会话认证接口尚未完成 CSRF token 接线；
                // 立即启用会打断现有登录和流式调用。当前缓解依赖 Cookie SameSite=Lax（application.yml
                // 显式配置）+ 生产 nginx 同源反代 + CORS 显式 Origin 白名单（严禁 "*"）。
                // 后续应给所有状态变更请求接入 CSRF token，再仅豁免无状态健康检查。
                .csrf(AbstractHttpConfigurer::disable)
                // CORS：本项目无 CorsConfigurationSource Bean，.cors(withDefaults()) 回退到 MVC CORS 配置，
                // 即 config/CorsConfig（WebMvcConfigurer）提供的显式 Origin 白名单；allowCredentials 的跨域
                // 开发模式（localhost:3001）在该处统一约束，生产同源部署缺省不注册任何 CORS 放行。
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> {
                        // permitAll 白名单（冻结合约 AUTH-v1）。
                        auth.requestMatchers(
                                "/auth/login",
                                "/auth/register",
                                // 启动器健康检查经前端 nginx 打 /api/health，必须免认证，否则 WaitHealth 永久失败。
                                "/health",
                                // Readiness remains REFUSING_TRAFFIC until ApplicationRunner finishes the vector load.
                                "/actuator/health/**",
                                // 容器 ERROR dispatch 落点：不放行则业务异常的标准错误响应会被安全链二次拦截成 401。
                                "/error")
                        .permitAll();
                        if (docsEnabled) {
                            // API 文档资源（knife4j / springdoc）：仅开发环境放行；生产经 app.docs.enabled=false
                            // 移出白名单（application-prod.yml 同时禁用端点本身）。
                            auth.requestMatchers(
                                    "/swagger-ui/**",
                                    "/swagger-ui.html",
                                    "/v3/api-docs/**",
                                    "/doc.html/**",
                                    "/webjars/**")
                            .permitAll();
                        }
                        auth.requestMatchers("/admin/**").hasRole(UserAccountService.ROLE_ADMIN)
                        .anyRequest().authenticated();
                })
                .exceptionHandling(exceptions -> exceptions
                        // 未认证：统一 401 JSON，不泄露受保护资源是否存在。
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未登录或会话已过期"))
                        // 已认证无权限（如 USER 访问 /admin/**）：403 JSON。
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问该资源")))
                // 会话固定防护：changeSessionId 是 Spring Security 默认策略，此处显式声明以确认未被禁用。
                // 注意：AuthController 的手工登录路径不经过 SessionManagementFilter 的认证检测，
                // 因此登录成功时另由控制器显式 changeSessionId，两条路径共同满足合约的防固定要求。
                .sessionManagement(session -> session.sessionFixation().changeSessionId());
        return http.build();
    }

    /**
     * 活跃会话索引（冻结合约 AUTH-v1）：{@link ActiveSessionService} 据此枚举并即时销毁被停用账号的会话。
     * SessionRegistryImpl 自身是 ApplicationListener，会自动消费容器会话创建/销毁事件保持同步。
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * 把 Servlet 容器的会话事件桥接进 Spring 上下文，使 SessionRegistryImpl 能感知会话自然过期与销毁。
     * 作为 HttpSessionListener Bean 由 Boot 自动注册到 Servlet 容器。
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String error, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ERROR_BODY_MAPPER.writeValue(response.getWriter(), new ApiError(error, message));
    }

    /**
     * 会话 principal（冻结合约 AUTH-v1）：仅携带 id/username/role，密码字段永久擦除。
     * 账号锁定/停用判定在登录入口由 UserAccountService 完成，停用后的即时吊销由 ActiveSessionService 负责，
     * 故 UserDetails 的四个状态位恒为 true。
     */
    public record PsychUserPrincipal(long id, String username, String role) implements UserDetails {

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority("ROLE_" + role));
        }

        @Override
        public String getPassword() {
            return null;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public boolean isAccountNonExpired() {
            return true;
        }

        @Override
        public boolean isAccountNonLocked() {
            return true;
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return true;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
