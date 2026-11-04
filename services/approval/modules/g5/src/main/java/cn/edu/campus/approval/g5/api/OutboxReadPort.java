package cn.edu.campus.approval.g5.api;
import java.util.UUID;
/** L07, read-only reference for the same-service aggregate adapter. */
public interface OutboxReadPort {
    OperationFacts.OutboxReference get(Principal principal,UUID id);
}
