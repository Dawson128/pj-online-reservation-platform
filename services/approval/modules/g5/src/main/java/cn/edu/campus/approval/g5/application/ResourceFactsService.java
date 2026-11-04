package cn.edu.campus.approval.g5.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import cn.edu.campus.approval.g5.api.IdentityException;
import cn.edu.campus.approval.g5.api.QualificationFacts;
import cn.edu.campus.approval.g5.domain.Credentials;
import cn.edu.campus.approval.g5.persistence.IdentityRepository;
import cn.edu.campus.approval.g5.persistence.ManagementRepository;
import cn.edu.campus.approval.g5.persistence.ResourceFactsRepository;

/** G4-only HTTP caller authentication precedes these operations in AuthFilter. No resource adjudication. */
@org.springframework.context.annotation.Profile("!shell")
@Service
public class ResourceFactsService {
    private final ManagementRepository directory;
    private final ResourceFactsRepository facts;
    private final TransactionTemplate read;
    public ResourceFactsService(ManagementRepository directory,ResourceFactsRepository facts,TransactionTemplate transaction) {
        this.directory=directory;this.facts=facts;
        read=new TransactionTemplate(transaction.getTransactionManager());read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);read.setTimeout(5);
    }
    public Map<String,Object> department(String id) {
        UUID department=ManagementService.id(id,"id");
        return snapshot(()->directory.department(department,false));
    }
    public QualificationFacts qualification(Map<String,Object> body) {
        if(body==null||!body.keySet().equals(Set.of("purpose","roomId","userIds","traceId"))) throw Credentials.invalid("body","须完整提供目的、房间、账户列表和追踪编号，不能包含额外字段");
        if(!"RESOURCE_IMPACT".equals(body.get("purpose"))) throw Credentials.invalid("purpose","仅支持 RESOURCE_IMPACT 资源变更资格查询");
        ManagementService.id(body.get("roomId"),"roomId");ManagementService.id(body.get("traceId"),"traceId");
        if(!(body.get("userIds") instanceof List<?> values)||values.isEmpty()||values.size()>100) throw Credentials.invalid("userIds","须提供 1—100 个账户 ID");
        List<UUID> ids=values.stream().map(value->ManagementService.id(value,"userIds")).toList();
        return snapshot(()->{
            var accounts=facts.accounts(ids);
            if(ids.stream().anyMatch(id->!accounts.containsKey(id))) throw new IdentityException(404,"NOT_FOUND","存在未知账户，不能返回不完整的资格事实");
            var checkedAt=facts.checkedAt();
            return new QualificationFacts(ids.stream().map(accounts::get).toList(),IdentityRepository.time(checkedAt),IdentityRepository.time(checkedAt.plusSeconds(5)));
        });
    }
    private <T> T snapshot(Supplier<T> operation) {
        try { return read.execute(status->operation.get()); }
        catch(DataAccessException|TransactionException unavailable) { throw new IdentityException(503,"DEPENDENCY_UNAVAILABLE","当前院系或账户事实暂不可用，请稍后重试"); }
    }
}
