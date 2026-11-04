package cn.edu.campus.approval.g5.application;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import cn.edu.campus.approval.g5.api.*;

/** L01/L02 facade keeps qualification's remote preparation outside identity's DB transactions. */
@org.springframework.context.annotation.Profile("!shell")
@Service
public class IdentityFacade implements IdentityPort {
    private final IdentityService identity;
    private final AuthorizationService authorization;
    public IdentityFacade(IdentityService identity,AuthorizationService authorization){this.identity=identity;this.authorization=authorization;}
    @Override public Principal introspect(String token){return identity.introspect(token);}
    @Override public BusinessFacts.Eligibility eligibility(Map<String,Object> input){return authorization.qualification("G2",input,UUID.randomUUID());}
}
