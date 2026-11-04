package cn.edu.campus.approval.g5.api;

import java.util.Map;
import java.util.Collections;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cn.edu.campus.approval.g5.application.ManagementService;
import cn.edu.campus.approval.g5.domain.Credentials;

@org.springframework.context.annotation.Profile("!shell")
@RestController
public class ManagementController {
    private final ManagementService management;
    public ManagementController(ManagementService management) { this.management=management; }
    @GetMapping("/api/m2/admin/accounts")
    public AuthResponses.Ok<?> accounts(HttpServletRequest request) { return list("ACCOUNT",request); }
    @GetMapping("/api/m2/admin/accounts/{id}")
    public AuthResponses.Ok<?> account(@PathVariable String id,HttpServletRequest request) { return detail("ACCOUNT",id,request); }
    @PostMapping("/api/m2/admin/accounts")
    public ResponseEntity<?> createAccount(@RequestBody Map<String,Object> body,HttpServletRequest request) { return mutate("G5-P07",null,body,request); }
    @PatchMapping("/api/m2/admin/accounts/{id}")
    public ResponseEntity<?> updateAccount(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) { return mutate("G5-P08",id,body,request); }
    @GetMapping("/api/m2/admin/roles")
    public AuthResponses.Ok<?> roles(HttpServletRequest request) { return list("ROLE",request); }
    @GetMapping("/api/m2/admin/roles/{id}")
    public AuthResponses.Ok<?> role(@PathVariable String id,HttpServletRequest request) { return detail("ROLE",id,request); }
    @PostMapping("/api/m2/admin/roles")
    public ResponseEntity<?> createRole(@RequestBody Map<String,Object> body,HttpServletRequest request) { return mutate("G5-P11",null,body,request); }
    @PatchMapping("/api/m2/admin/roles/{id}")
    public ResponseEntity<?> updateRole(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) { return mutate("G5-P12",id,body,request); }
    @GetMapping("/api/m2/admin/permissions")
    public AuthResponses.Ok<?> permissions(HttpServletRequest request) { return new AuthResponses.Ok<>(management.permissions(AuthFilter.token(request)),AuthFilter.trace(request)); }
    @PutMapping("/api/m2/admin/accounts/{id}/roles")
    public ResponseEntity<?> bindRoles(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) { return mutate("G5-P13",id,body,request); }
    @GetMapping("/api/m2/departments")
    public AuthResponses.Ok<?> departments(HttpServletRequest request) { return list("DEPARTMENT",request); }
    @PostMapping("/api/m2/admin/departments")
    public ResponseEntity<?> createDepartment(@RequestBody Map<String,Object> body,HttpServletRequest request) { return mutate("G5-P16",null,body,request); }
    @PatchMapping("/api/m2/admin/departments/{id}")
    public ResponseEntity<?> updateDepartment(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) { return mutate("G5-P17",id,body,request); }
    private AuthResponses.Ok<?> list(String kind,HttpServletRequest request) { return new AuthResponses.Ok<>(management.list(AuthFilter.token(request),kind,request.getParameterMap()),AuthFilter.trace(request)); }
    private AuthResponses.Ok<?> detail(String kind,String id,HttpServletRequest request) { return new AuthResponses.Ok<>(management.detail(AuthFilter.token(request),kind,id),AuthFilter.trace(request)); }
    private ResponseEntity<?> mutate(String operation,String id,Map<String,Object> body,HttpServletRequest request) {
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));
        if(keys.size()!=1) throw Credentials.invalid("Idempotency-Key","须提供一个请求键");
        var result=management.mutate(AuthFilter.token(request),operation,id,keys.getFirst(),body,AuthFilter.trace(request));
        return ResponseEntity.status(result.status()).header("Idempotency-Replayed",Boolean.toString(result.replayed()))
                .header("X-Trace-Id",result.body().get("traceId").toString()).body(result.body());
    }
}
