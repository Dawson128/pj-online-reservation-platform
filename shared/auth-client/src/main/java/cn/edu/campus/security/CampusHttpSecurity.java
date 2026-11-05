package cn.edu.campus.security;

import java.net.URI;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** G6 assembly. Empty rules mean closed business routes, not permission to every logged-in user. */
public final class CampusHttpSecurity {
    private CampusHttpSecurity() { }
    public static SecurityFilterChain build(HttpSecurity http,Environment environment,List<ApiAccessRule> rules,String prefix) throws Exception {
        Set<String> seen=new HashSet<>();List<RemoteSessionFilter.Route> routes=new ArrayList<>();
        for(ApiAccessRule rule:rules) {
            if(!rule.path().startsWith(prefix)||!seen.add(rule.method()+" "+rule.path()))throw new IllegalArgumentException("Duplicate or foreign service API rule");
            routes.add(new RemoteSessionFilter.Route(rule,rule.matcher()));
        }
        var client=new G5IntrospectionClient(URI.create(environment.getProperty("G5_AUTH_BASE_URL","http://127.0.0.1:8082")),
                environment.getProperty("G5_AUTH_ALLOW_HTTP",Boolean.class,false),group->environment.getProperty("G5_SERVICE_"+group+"_TOKEN"));
        return http.httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
            .csrf(AbstractHttpConfigurer::disable) // Registered unsafe requests are checked atomically by G5; all other paths stay denied.
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new RemoteSessionFilter(client,routes),UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth->{
                auth.requestMatchers("/actuator/health/liveness").permitAll();
                for(var route:routes)auth.requestMatchers(route.matcher()).hasAuthority(route.rule().action());
                auth.anyRequest().denyAll();
            })
            .exceptionHandling(errors->errors
                .authenticationEntryPoint((request,response,error)->AuthErrors.write(request,response,AuthFailure.unauthenticated()))
                .accessDeniedHandler((request,response,error)->AuthErrors.write(request,response,new AuthFailure(403,"FORBIDDEN","没有此操作权限"))))
            .build();
    }
}
