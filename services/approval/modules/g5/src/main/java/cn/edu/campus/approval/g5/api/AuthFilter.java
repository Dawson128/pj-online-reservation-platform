package cn.edu.campus.approval.g5.api;

import java.io.IOException;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import cn.edu.campus.approval.g5.application.IdentityService;
import cn.edu.campus.approval.g5.domain.Credentials;

@org.springframework.context.annotation.Profile("!shell")
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthFilter extends OncePerRequestFilter {
    public static final String SERVICE = "g5.service";
    public static final String COOKIE = "PJ_SESSION";
    public static final String PRINCIPAL = "g5.principal";
    public static final String TRACE = "g5.trace";
    private final IdentityService identities;
    private final ObjectMapper json;
    private final Environment environment;

    public AuthFilter(IdentityService identities, ObjectMapper json, Environment environment) {
        this.identities = identities;
        this.json = json;
        this.environment = environment;
        Set<String> configuredTokens = new HashSet<>();
        for (String group : new String[]{"G1", "G2", "G3", "G4", "G6"}) {
            String token = environment.getProperty("G5_SERVICE_" + group + "_TOKEN");
            if (token != null && !token.isEmpty()
                    && (token.length() < 32 || token.length() > 256 || !configuredTokens.add(token))) {
                throw new IllegalStateException("Service credentials must be distinct and 32-256 characters");
            }
        }
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/") && !request.getServletPath().startsWith("/internal/");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID trace = UUID.randomUUID();
        request.setAttribute(TRACE, trace);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Trace-Id", trace.toString());
        try {
            // Use the same decoded servlet path as controller mapping, including percent-encoded prefixes.
            String path = request.getServletPath();
            if (path.startsWith("/internal/")) {
                var authorization=java.util.Collections.list(request.getHeaders("Authorization"));
                if(authorization.size()!=1) throw new IdentityException(403,"FORBIDDEN","须提供唯一的服务身份");
                request.setAttribute(SERVICE,requireService(authorization.getFirst(),path));
            } else if (!path.equals("/api/m2/auth/login") && !path.equals("/api/m2/auth/logout")) {
                String token = token(request);
                Principal principal = identities.introspect(token);
                request.setAttribute(PRINCIPAL, principal);
                if (path.startsWith("/api/m2/admin/")) IdentityService.requireSystemManager(principal);
                if (Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod())) {
                    identities.requireCsrf(token, request.getHeader("X-CSRF-Token"));
                }
            }
            // Authentication precedes payload/query validation on protected endpoints.
            boolean listQuery = request.getMethod().equals("GET") && Set.of("/api/m2/admin/accounts","/api/m2/admin/roles","/api/m2/departments").contains(path);
            if (request.getQueryString() != null && !request.getQueryString().isEmpty() && !listQuery) {
                throw Credentials.invalid("query", "此接口不接受查询参数");
            }
            chain.doFilter(request, response);
        } catch (IdentityException failure) {
            try {
                Principal actor = (Principal) request.getAttribute(PRINCIPAL);
                identities.recordRejection("auth:request-denied", actor, failure.code(), trace);
            } catch (IdentityException unavailable) { failure = unavailable; }
            writeFailure(response, failure, trace);
        }
    }
    private String requireService(String header,String path) {
        Set<String> allowed;
        if(path.startsWith("/internal/departments/")||path.equals("/internal/accounts/qualification-facts")) allowed=Set.of("G4");
        else if(path.equals("/internal/authorization/eligibility"))allowed=Set.of("G2","G3","G4");
        else if(path.startsWith("/internal/maintenance/"))allowed=Set.of("G3");
        else if(path.startsWith("/internal/notification-recipients/"))allowed=Set.of("G6");
        else if(path.equals("/internal/config"))allowed=Set.of("G1","G2","G3","G4","G6");
        else allowed=Set.of("G1","G3","G4","G6");
        if(header!=null&&header.startsWith("Bearer ")) {
            String candidate=header.substring(7);
            for(String group:allowed){String configured=environment.getProperty("G5_SERVICE_"+group+"_TOKEN");
                if(configured!=null&&configured.length()>=32&&Credentials.same(configured,candidate))return group;}
        }
        throw new IdentityException(403,"FORBIDDEN","服务身份无效或不允许调用此接口");
    }
    public static String token(HttpServletRequest request) {
        String result = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (COOKIE.equals(cookie.getName())) {
                    if (result != null) { throw Credentials.unauthenticated(); }
                    result = cookie.getValue();
                }
            }
        }
        return result;
    }
    public static UUID trace(HttpServletRequest request) {
        Object value = request.getAttribute(TRACE);
        return value instanceof UUID id ? id : UUID.randomUUID();
    }
    private void writeFailure(HttpServletResponse response, IdentityException failure, UUID trace) throws IOException {
        response.setStatus(failure.status());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (failure.retryAfterSeconds() != null) { response.setHeader("Retry-After", failure.retryAfterSeconds().toString()); }
        json.writeValue(response.getOutputStream(), AuthResponses.error(failure, trace));
    }
}
