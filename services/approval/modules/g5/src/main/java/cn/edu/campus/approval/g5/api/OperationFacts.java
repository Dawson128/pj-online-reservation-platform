package cn.edu.campus.approval.g5.api;

import java.util.*;

/** G5-L04/L05/L07 wire DTOs. No event payload, credentials, or email is exposed. */
public final class OperationFacts {
    public record Ref(String type,UUID id) { }
    public record Result(String outcome,Ref record,String resultVersion,String effectiveAt) { }
    public record Operation(UUID id,String version,String createdAt,String updatedAt,String ownerGroup,Ref source,
        UUID roomId,String scopeKind,String category,UUID eventId,UUID actorId,UUID operationId,String status,String phase,
        String acceptedAt,int attemptCount,String nextRetryAt,AuthResponses.Error error,boolean needsAttention,
        Result result,String statusUrl,List<String> allowedActions) { }
    public record Page(List<Operation> items,int page,int pageSize,int total,String asOf) { }
    public record PendingRef(String kind,UUID id,String since,String detailUrl) { }
    public record Counters(int pendingCount,int attentionCount,int outboxCount,Integer oldestOutboxAgeSeconds,
        Integer oldestPendingAgeSeconds,PendingRef oldestPendingRef) { }
    public record Metrics(String asOf,Counters operations,Object notifications) { }
    public record OutboxReference(UUID id,UUID eventId,Ref source,UUID roomId,String ownerGroup,String destination,
        String occurredAt,String acceptedAt,int attemptCount,String nextRetryAt,AuthResponses.Error error) { }
    private OperationFacts() { }
}
