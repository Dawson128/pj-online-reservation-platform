package cn.edu.campus.approval.g5.api;
import java.util.Map;
import java.util.UUID;
/** L04, joins the consumer's read-only snapshot; all G5 delivery operations are SYSTEM. */
public interface OperationReadPort {
    OperationFacts.Page list(Principal principal,Map<String,Object> query);
    OperationFacts.Operation get(Principal principal,UUID id);
    OperationFacts.Metrics metrics(Principal principal,UUID roomId);
}
