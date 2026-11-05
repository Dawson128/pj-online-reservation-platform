package cn.edu.campus.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Explicit coarse action gate. Controllers still enforce current object scope/ownership. */
public record ApiAccessRule(String module, String method, String path, String action) {
    public ApiAccessRule {
        String prefix=switch(module) { case "G1","G4" -> "/api/m1/"; case "G3","G6" -> "/api/m3/"; default -> throw new IllegalArgumentException("Unsupported consumer module"); };
        if(!path.startsWith(prefix)||path.contains("..")||path.contains("\\")||path.contains("%")||action==null||action.isBlank())throw new IllegalArgumentException("Invalid API access rule");
        if(!java.util.Set.of("GET","HEAD","POST","PUT","PATCH","DELETE").contains(method))throw new IllegalArgumentException("Unsupported API method");
    }
    public RequestMatcher matcher() { return PathPatternRequestMatcher.pathPattern(HttpMethod.valueOf(method),path); }
}
