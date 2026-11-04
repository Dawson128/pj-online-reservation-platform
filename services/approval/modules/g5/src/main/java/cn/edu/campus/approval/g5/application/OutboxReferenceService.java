package cn.edu.campus.approval.g5.application;
import java.util.UUID;
import org.springframework.stereotype.Service;
import cn.edu.campus.approval.g5.api.*;
@org.springframework.context.annotation.Profile("!shell")
@Service
public class OutboxReferenceService implements OutboxReadPort {
    private final OperationService operations;
    public OutboxReferenceService(OperationService operations){this.operations=operations;}
    @Override public OperationFacts.OutboxReference get(Principal principal,UUID id){return operations.outbox(principal,id);}
}
