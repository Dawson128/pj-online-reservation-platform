package cn.edu.campus.approval;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.RequestDispatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** G6 boundary with G5's real servlet session/CSRF/service filter ahead of this chain. */
@Configuration
class BootstrapSecurity {
    @Bean
    SecurityFilterChain bootstrapFilterChain(HttpSecurity http, Environment environment) throws Exception {
        boolean shell = environment.acceptsProfiles(Profiles.of("shell"));
        return http.httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            // G5 validates CSRF against the persisted session before the security chain.
            // Every other business path stays denied until its owner explicitly integrates it.
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/actuator/health/liveness").permitAll();
                if (!shell) {
                    auth.requestMatchers("/actuator/health", "/actuator/health/**",
                        "/api/m2/auth/login", "/api/m2/auth/logout", "/api/m2/auth/me", "/api/m2/auth/change-password",
                        "/api/m2/departments", "/api/m2/admin/accounts", "/api/m2/admin/accounts/**",
                        "/api/m2/admin/roles", "/api/m2/admin/roles/**", "/api/m2/admin/permissions",
                        "/api/m2/admin/departments", "/api/m2/admin/departments/**", "/api/m2/admin/config",
                        "/internal/auth/introspect", "/internal/config", "/internal/departments/*",
                        "/internal/accounts/qualification-facts", "/internal/authorization/**",
                        "/internal/maintenance/**", "/internal/notification-recipients/**").permitAll();
                }
                auth.anyRequest().denyAll();
            })
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> writeBoundaryError(request, response, 401, "UNAUTHENTICATED", shell))
                .accessDeniedHandler((request, response, error) -> writeBoundaryError(request, response, 403, "FORBIDDEN", shell)))
            .build();
    }

    private static void writeBoundaryError(HttpServletRequest request, HttpServletResponse response,
            int status, String code, boolean shell) throws IOException {
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        // Keep unknown management paths denied, including after an unmapped health path
        // dispatches to /error. Returning 404 does not grant access to any endpoint.
        if (!shell && (request.getServletPath().startsWith("/actuator/")
                || original instanceof String path && path.startsWith("/actuator/"))) {
            response.setStatus(404);
            return;
        }
        writeError(response, status, code);
    }

    private static void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"Authentication is required\",\"traceId\":\""
            + UUID.randomUUID() + "\",\"details\":{\"fields\":[],\"conflicts\":[],\"currentVersion\":null,\"operationId\":null,\"retryAfterSeconds\":null}}");
    }
}
