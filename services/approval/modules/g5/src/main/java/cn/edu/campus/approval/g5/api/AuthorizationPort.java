package cn.edu.campus.approval.g5.api;

import java.util.Map;
import java.util.UUID;

/** G5-L06. Call before opening the consumer transaction; may fetch G4 room facts. */
public interface AuthorizationPort {
    default BusinessFacts.AuthorizationBatch check(String actorSession, Map<String,Object> request) {
        return check(actorSession,request,UUID.randomUUID());
    }
    BusinessFacts.AuthorizationBatch check(String actorSession, Map<String,Object> request, UUID traceId);
}
