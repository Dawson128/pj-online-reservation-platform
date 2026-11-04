package cn.edu.campus.approval.g5.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable provider facts. Versions are decimal strings; timestamps carry the +08:00 offset. */
public final class BusinessFacts {
    public record ScopeFacts(UUID roomId, boolean schoolWide, UUID deptId, boolean enabled, String roomVersion, String asOf) { }
    public record Eligibility(UUID subjectId, boolean active, boolean hasPermission, boolean scopeMatched,
                              boolean canAccessRoom, boolean eligible, String accountVersion, String permissionVersion,
                              String roomVersion, String checkedAt, String validUntil) { }
    public record Decision(UUID checkId, String action, Map<String,Object> target, boolean allowed, String roomVersion, String reason) { }
    public record AuthorizationBatch(UUID actorId, String accountVersion, String permissionVersion, String checkedAt,
                                     String validUntil, List<Decision> items) { }
    public record Candidate(UUID userId, String displayName, String qualificationSummary) { }
    public record CandidatePage(List<Candidate> items, int page, int pageSize, int total, String asOf) { }
    public record Recipient(UUID userId, String email, String accountVersion, String permissionVersion, List<String> branches) { }
    public record Excluded(UUID userId, String reason) { }
    public record Recipients(String outcome, List<Recipient> recipients, List<Excluded> excluded, ScopeFacts scopeFacts, String resolvedAt) { }
    public record RecipientValidation(boolean eligible, Recipient recipient, String checkedAt) { }
    private BusinessFacts() { }
}
