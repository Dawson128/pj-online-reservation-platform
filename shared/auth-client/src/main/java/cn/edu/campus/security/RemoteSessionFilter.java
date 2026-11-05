package cn.edu.campus.security;

import java.io.IOException;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installed inside Spring Security only. Never registered as a second servlet filter. */
final class RemoteSessionFilter extends OncePerRequestFilter {
    record Route(ApiAccessRule rule,RequestMatcher matcher) { }
    private final G5IntrospectionClient client;
    private final List<Route> routes;
    RemoteSessionFilter(G5IntrospectionClient client,List<Route> routes) {this.client=client;this.routes=List.copyOf(routes);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        Route route=routes.stream().filter(candidate->candidate.matcher().matches(request)).findFirst().orElse(null);
        if(route==null){chain.doFilter(request,response);return;}
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Trace-Id",AuthErrors.trace(request).toString());
        try {
            String token=null;
            if(request.getCookies()!=null)for(Cookie cookie:request.getCookies())if("PJ_SESSION".equals(cookie.getName())) {
                if(token!=null)throw AuthFailure.unauthenticated(); token=cookie.getValue();
            }
            if(token==null||token.length()<32||token.length()>256)throw AuthFailure.unauthenticated();
            var csrfHeaders=Collections.list(request.getHeaders("X-CSRF-Token"));
            if(csrfHeaders.size()>1)throw new AuthFailure(400,"VALIDATION_ERROR","安全校验头不能重复");
            CampusPrincipal principal=client.authenticate(route.rule().module(),token,request.getMethod(),csrfHeaders.isEmpty()?null:csrfHeaders.getFirst());
            var authorities=principal.grants().stream().map(grant->new SimpleGrantedAuthority(grant.action())).toList();
            var authentication=UsernamePasswordAuthenticationToken.authenticated(principal,null,authorities);
            var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(authentication);SecurityContextHolder.setContext(context);
            request.setAttribute("campus.principal",principal);
            chain.doFilter(request,response);
        } catch(AuthFailure failure) {AuthErrors.write(request,response,failure);}
        finally {SecurityContextHolder.clearContext();}
    }
}
