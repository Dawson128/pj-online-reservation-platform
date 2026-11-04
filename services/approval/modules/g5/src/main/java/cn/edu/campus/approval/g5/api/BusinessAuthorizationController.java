package cn.edu.campus.approval.g5.api;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import cn.edu.campus.approval.g5.application.*;
import cn.edu.campus.approval.g5.domain.Credentials;

@org.springframework.context.annotation.Profile("!shell")
@RestController
public class BusinessAuthorizationController {
    private final AuthorizationService authorization;
    private final NotificationRecipientsService notifications;
    public BusinessAuthorizationController(AuthorizationService authorization,NotificationRecipientsService notifications){this.authorization=authorization;this.notifications=notifications;}
    private String session(HttpServletRequest request){var values=Collections.list(request.getHeaders("X-Actor-Session"));if(values.size()!=1||values.getFirst().length()<32||values.getFirst().length()>256)throw Credentials.unauthenticated();return values.getFirst();}
    @PostMapping("/internal/authorization/check")
    public AuthResponses.Ok<?> check(@RequestBody Map<String,Object> body,HttpServletRequest request){return new AuthResponses.Ok<>(authorization.check(session(request),body,AuthFilter.trace(request)),AuthFilter.trace(request));}
    @PostMapping("/internal/authorization/eligibility")
    public AuthResponses.Ok<?> qualification(@RequestBody Map<String,Object> body,HttpServletRequest request){return new AuthResponses.Ok<>(authorization.qualification((String)request.getAttribute(AuthFilter.SERVICE),body,AuthFilter.trace(request)),AuthFilter.trace(request));}
    @PostMapping("/internal/maintenance/assignee-candidates")
    public AuthResponses.Ok<?> candidates(@RequestBody Map<String,Object> body,HttpServletRequest request){return new AuthResponses.Ok<>(authorization.candidates(session(request),body,AuthFilter.trace(request)),AuthFilter.trace(request));}
    @PostMapping("/internal/maintenance/assignee-eligibility")
    public AuthResponses.Ok<?> assignee(@RequestBody Map<String,Object> body,HttpServletRequest request){return new AuthResponses.Ok<>(authorization.assignee(session(request),body,AuthFilter.trace(request)),AuthFilter.trace(request));}
    @PostMapping("/internal/notification-recipients/query")
    public AuthResponses.Ok<?> query(@RequestBody Map<String,Object> body,HttpServletRequest request){return new AuthResponses.Ok<>(notifications.query(body,AuthFilter.trace(request)),AuthFilter.trace(request));}
    @PostMapping("/internal/notification-recipients/validate")
    public AuthResponses.Ok<?> validate(@RequestBody Map<String,Object> body,HttpServletRequest request){return new AuthResponses.Ok<>(notifications.validate(body,AuthFilter.trace(request)),AuthFilter.trace(request));}
}
