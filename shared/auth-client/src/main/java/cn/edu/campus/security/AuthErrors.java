package cn.edu.campus.security;

import java.io.IOException;
import java.util.*;
import jakarta.servlet.http.*;
import tools.jackson.databind.json.JsonMapper;

public final class AuthErrors {
    private static final JsonMapper JSON=new JsonMapper();
    private AuthErrors() { }
    public static UUID trace(HttpServletRequest request) {
        Object current=request.getAttribute("campus.traceId");
        if(current instanceof UUID id)return id;
        UUID id=UUID.randomUUID();request.setAttribute("campus.traceId",id);return id;
    }
    public static void write(HttpServletRequest request,HttpServletResponse response,AuthFailure failure) throws IOException {
        response.setStatus(failure.status());response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Trace-Id",trace(request).toString());
        Map<String,Object> details=new LinkedHashMap<>();details.put("fields",List.of());details.put("conflicts",List.of());
        details.put("currentVersion",null);details.put("operationId",null);details.put("retryAfterSeconds",null);
        JSON.writeValue(response.getOutputStream(),Map.of("code",failure.code(),"message",failure.getMessage(),"traceId",trace(request).toString(),"details",details));
    }
}
