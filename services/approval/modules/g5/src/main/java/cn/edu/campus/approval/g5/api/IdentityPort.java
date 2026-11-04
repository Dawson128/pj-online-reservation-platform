package cn.edu.campus.approval.g5.api;

/** G5-L01. Never persist the supplied raw session token. */
public interface IdentityPort {
    Principal introspect(String sessionToken);
    /** G5-L02; FINAL_APPROVAL only, call outside the consumer transaction. */
    BusinessFacts.Eligibility eligibility(java.util.Map<String,Object> input);
}
