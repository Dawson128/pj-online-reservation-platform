package cn.edu.campus.approval.g5.api;

import java.util.Map;
import java.util.Collections;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cn.edu.campus.approval.g5.application.ConfigurationService;
import cn.edu.campus.approval.g5.application.ManagementService;
import cn.edu.campus.approval.g5.domain.Credentials;

@org.springframework.context.annotation.Profile("!shell")
@RestController
public class ConfigurationController {
    private final ConfigurationService configuration;
    private final ManagementService management;
    public ConfigurationController(ConfigurationService configuration,ManagementService management) { this.configuration=configuration;this.management=management; }
    @GetMapping("/api/m2/admin/config")
    public AuthResponses.Ok<?> get(HttpServletRequest request) { return new AuthResponses.Ok<>(configuration.managed(AuthFilter.token(request)),AuthFilter.trace(request)); }
    @PatchMapping("/api/m2/admin/config")
    public ResponseEntity<?> update(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));
        if(keys.size()!=1) throw Credentials.invalid("Idempotency-Key","须提供一个请求键");
        var result=management.mutate(AuthFilter.token(request),"G5-P19",null,keys.getFirst(),body,AuthFilter.trace(request));
        return ResponseEntity.status(result.status()).header("Idempotency-Replayed",Boolean.toString(result.replayed())).header("X-Trace-Id",result.body().get("traceId").toString()).body(result.body());
    }
    @GetMapping("/internal/config")
    public AuthResponses.Ok<?> internal(HttpServletRequest request) { return new AuthResponses.Ok<>(configuration.current(),AuthFilter.trace(request)); }
}
