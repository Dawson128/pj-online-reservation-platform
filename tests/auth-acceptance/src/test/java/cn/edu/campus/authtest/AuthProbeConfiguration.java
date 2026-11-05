package cn.edu.campus.authtest;

import cn.edu.campus.security.ApiAccessRule;
import cn.edu.campus.security.CampusPrincipal;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Explicit test-classpath injection only. Never packaged in any product image or service jar. */
@Configuration(proxyBeanMethods=false)
@Profile("auth-it")
public class AuthProbeConfiguration {
    private ApiAccessRule rule(Environment env,String method,String suffix,String action) {
        String module=env.getRequiredProperty("AUTH_IT_MODULE");
        return new ApiAccessRule(module,method,(module.equals("G4")?"/api/m1/":"/api/m3/")+"__auth_acceptance/"+suffix,action);
    }
    @Bean ApiAccessRule probeRead(Environment env){return rule(env,"GET","read","system:manage");}
    @Bean ApiAccessRule probePost(Environment env){return rule(env,"POST","write","system:manage");}
    @Bean ApiAccessRule probePut(Environment env){return rule(env,"PUT","write","system:manage");}
    @Bean ApiAccessRule probePatch(Environment env){return rule(env,"PATCH","write","system:manage");}
    @Bean ApiAccessRule probeDelete(Environment env){return rule(env,"DELETE","write","system:manage");}
    @Bean ApiAccessRule probeDenied(Environment env){return rule(env,"GET","denied","room:manage");}

    @RestController
    static class ProbeController {
        @GetMapping({"/api/m1/__auth_acceptance/read","/api/m3/__auth_acceptance/read",
                "/api/m1/__auth_acceptance/denied","/api/m3/__auth_acceptance/denied",
                "/api/m1/__auth_acceptance/unregistered","/api/m3/__auth_acceptance/unregistered"})
        Map<String,Object> read(@AuthenticationPrincipal CampusPrincipal principal) {
            return Map.of("userId",principal.userId().toString(),"accountVersion",principal.accountVersion(),"permissionVersion",principal.permissionVersion());
        }
        @RequestMapping(path={"/api/m1/__auth_acceptance/write","/api/m3/__auth_acceptance/write"},
                method={RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE})
        Map<String,Object> write(@AuthenticationPrincipal CampusPrincipal principal){return Map.of("written",true,"userId",principal.userId().toString());}
    }
}
