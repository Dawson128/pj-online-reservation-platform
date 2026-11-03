package cn.edu.campus.maintenance;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** G6 assembly only. G5 session authentication must replace this fail-closed boundary. */
@Configuration
class BootstrapSecurity {
    @Bean
    SecurityFilterChain bootstrapFilterChain(HttpSecurity http) throws Exception {
        return http.httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/liveness").permitAll()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> writeError(response, 401, "UNAUTHENTICATED"))
                .accessDeniedHandler((request, response, error) -> writeError(response, 403, "FORBIDDEN")))
            .build();
    }

    private static void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"Authentication is required\",\"traceId\":\""
            + UUID.randomUUID() + "\",\"details\":{\"fields\":[],\"conflicts\":[],\"currentVersion\":null,\"operationId\":null,\"retryAfterSeconds\":null}}");
    }
}
