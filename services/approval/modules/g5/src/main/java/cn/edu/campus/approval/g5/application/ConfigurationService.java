package cn.edu.campus.approval.g5.application;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import cn.edu.campus.approval.g5.api.BusinessConfiguration;
import cn.edu.campus.approval.g5.api.ConfigurationPort;
import cn.edu.campus.approval.g5.api.IdentityException;
import cn.edu.campus.approval.g5.persistence.ManagementRepository;

@org.springframework.context.annotation.Profile("!shell")
@Service
public class ConfigurationService implements ConfigurationPort {
    private final ManagementRepository repository;
    private final IdentityService identities;
    private final TransactionTemplate read;
    public ConfigurationService(ManagementRepository repository,IdentityService identities,TransactionTemplate transaction) {
        this.repository=repository;this.identities=identities;
        read=new TransactionTemplate(transaction.getTransactionManager());read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public BusinessConfiguration managed(String token) {
        return snapshot(()->{IdentityService.requireSystemManager(identities.introspect(token));return convert(repository.configuration(false));});
    }
    @Override public BusinessConfiguration current() { return snapshot(()->convert(repository.configuration(false))); }
    @SuppressWarnings("unchecked") private BusinessConfiguration convert(Map<String,Object> record) {
        return new BusinessConfiguration((String)record.get("version"),(String)record.get("effectiveAt"),(Map<String,Integer>)record.get("values"));
    }
    private BusinessConfiguration snapshot(Supplier<BusinessConfiguration> operation) {
        try { return read.execute(status->operation.get()); }
        catch(DataAccessException|TransactionException unavailable) { throw new IdentityException(503,"AUTH_UNAVAILABLE","配置暂不可用，请稍后重试"); }
    }
}
