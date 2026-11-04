package cn.edu.campus.approval.g5.api;

import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import cn.edu.campus.approval.g5.application.ManagementService;
import cn.edu.campus.approval.g5.application.ResourceFactsService;
import cn.edu.campus.approval.g5.domain.Credentials;

@org.springframework.context.annotation.Profile("!shell")
@RestController
public class ResourceFactsController {
    private final ResourceFactsService facts;
    public ResourceFactsController(ResourceFactsService facts) { this.facts=facts; }
    @GetMapping("/internal/departments/{id}")
    public AuthResponses.Ok<?> department(@PathVariable String id,HttpServletRequest request) {
        if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null) throw Credentials.invalid("body","院系查询不接受请求正文");
        return new AuthResponses.Ok<>(facts.department(id),AuthFilter.trace(request));
    }
    @PostMapping("/internal/accounts/qualification-facts")
    public AuthResponses.Ok<?> qualification(@RequestBody Map<String,Object> body,HttpServletRequest request,HttpServletResponse response) {
        UUID trace=ManagementService.id(body==null?null:body.get("traceId"),"traceId");
        request.setAttribute(AuthFilter.TRACE,trace);response.setHeader("X-Trace-Id",trace.toString());
        return new AuthResponses.Ok<>(facts.qualification(body),trace);
    }
}
