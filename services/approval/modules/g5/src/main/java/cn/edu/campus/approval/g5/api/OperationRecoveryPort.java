package cn.edu.campus.approval.g5.api;
import java.util.Map;
import java.util.UUID;
/** L05 requeues an original delivery; never replays an account/configuration mutation. */
public interface OperationRecoveryPort {
    OperationFacts.Operation retry(Principal principal,UUID id,Map<String,Object> reasonBody,UUID key);
}
