package cn.edu.campus.approval.g5.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import cn.edu.campus.approval.g5.api.IdentityException;
import cn.edu.campus.approval.g5.api.Principal;
import cn.edu.campus.approval.g5.domain.Credentials;
import cn.edu.campus.approval.g5.domain.PermissionCatalog;
import cn.edu.campus.approval.g5.domain.ConfigurationRules;
import cn.edu.campus.approval.g5.persistence.AuthAudit;
import cn.edu.campus.approval.g5.persistence.ManagementRepository;

@org.springframework.context.annotation.Profile("!shell")
@Service
public class ManagementService {
    private final ManagementRepository repository;
    private final IdentityService identities;
    private final Credentials credentials;
    private final AuthAudit audit;
    private final ObjectMapper json;
    private final Environment environment;
    private final RoomScopeClient rooms;
    private final ConfigurationRules configurationRules;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    public record Result(Map<String, Object> body, int status, boolean replayed) { }
    public ManagementService(ManagementRepository repository, IdentityService identities, Credentials credentials, AuthAudit audit, ObjectMapper json, Environment environment, RoomScopeClient rooms, ConfigurationRules configurationRules, TransactionTemplate transaction) {
        this.repository=repository; this.identities=identities; this.credentials=credentials; this.audit=audit; this.json=json; this.environment=environment; this.rooms=rooms; this.configurationRules=configurationRules; this.write=transaction;
        read=new TransactionTemplate(transaction.getTransactionManager()); read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Map<String, Object> list(String token, String kind, Map<String, String[]> query) {
        return read.execute(status -> {
            Principal actor=identities.introspect(token); if (!kind.equals("DEPARTMENT")) IdentityService.requireSystemManager(actor);
            Set<String> allowed=kind.equals("ACCOUNT") ? Set.of("page","pageSize","keyword","enabled","departmentId","roleId") : kind.equals("ROLE") ? Set.of("page","pageSize","keyword","builtIn") : Set.of("page","pageSize","keyword","enabled");
            Map<String,Object> filters=new LinkedHashMap<>(); int page=1,size=20;
            for (var entry:query.entrySet()) {
                String field=entry.getKey(); String[] values=entry.getValue();
                if (!allowed.contains(field) || values.length!=1 || values[0].isEmpty()) throw Credentials.invalid(field,"未知、重复或空查询参数");
                String value=values[0];
                switch (field) {
                    case "page" -> page=integer(value,1,1000000,field);
                    case "pageSize" -> size=integer(value,1,100,field);
                    case "enabled", "builtIn" -> { if (!Set.of("true","false").contains(value)) throw Credentials.invalid(field,"须为 true 或 false"); filters.put(field,Boolean.valueOf(value)); }
                    case "departmentId", "roleId" -> filters.put(field,id(value,field));
                    default -> filters.put(field,Credentials.text(value,1,100,field,true));
                }
            }
            if (kind.equals("DEPARTMENT")) filters.putIfAbsent("enabled",true);
            return repository.list(kind,filters,page,size);
        });
    }
    public Map<String,Object> detail(String token,String kind,String value) {
        return read.execute(status -> { IdentityService.requireSystemManager(identities.introspect(token)); UUID id=id(value,"id"); return kind.equals("ACCOUNT") ? repository.account(id,false) : repository.role(id); });
    }
    public Map<String,Object> permissions(String token) {
        return read.execute(status->{ IdentityService.requireSystemManager(identities.introspect(token)); return Map.of("items",PermissionCatalog.ALL); });
    }
    public Result mutate(String token,String operation,String target,String keyValue,Map<String,Object> body,UUID trace) {
        IdentityService.requireSystemManager(identities.introspect(token));
        UUID key=id(keyValue,"Idempotency-Key"), targetId=target==null ? null : id(target,"id");
        Map<String,Object> clean=validate(operation,body);
        String method=Set.of("G5-P07","G5-P11","G5-P16").contains(operation) ? "POST" : operation.equals("G5-P13") ? "PUT" : "PATCH";
        String path=switch(operation) {
            case "G5-P19" -> "/api/m2/admin/config";
            case "G5-P11" -> "/api/m2/admin/roles";
            case "G5-P12" -> "/api/m2/admin/roles/"+targetId;
            case "G5-P16" -> "/api/m2/admin/departments";
            case "G5-P17" -> "/api/m2/admin/departments/"+targetId;
            default -> "/api/m2/admin/accounts"+(targetId==null ? "" : "/"+targetId)+(operation.equals("G5-P13") ? "/roles" : "");
        };
        String digest=hmac(clean);
        Result prior=read.execute(status->{ Principal actor=identities.introspect(token); IdentityService.requireSystemManager(actor); return replay(actor.userId(),method,path,key,digest,trace); });
        if(prior!=null) return prior;
        if(clean.containsKey("grants")) rooms.requireAll(scopeIds(clean.get("grants"),"roomId"),trace);
        return write.execute(status -> {
            repository.lockManagement(); Principal actor=identities.introspect(token); IdentityService.requireSystemManager(actor);
            Result repeated=replay(actor.userId(),method,path,key,digest,trace); if(repeated!=null) return repeated;
            if(clean.containsKey("grants")) for(UUID department:scopeIds(clean.get("grants"),"departmentId")) {
                try { repository.department(department,true); }
                catch(IdentityException missing) { if(missing.status()==404) throw Credentials.invalid("grants","指定院系不存在"); throw missing; }
            }
            Map<String,Object> before=Map.of(),result; UUID subject=targetId; String createdKind=null;
            switch(operation) {
                case "G5-P07" -> {
                    activeDepartment((UUID)clean.get("departmentId")); List<UUID> roles=roles(clean.get("roleIds"));
                    subject=repository.createAccount(clean,credentials.passwordHash((String)clean.get("initialPassword")));
                    repository.bindRoles(subject,roles,false); result=repository.account(subject,false); createdKind="ACCOUNT";
                }
                case "G5-P08" -> {
                    before=repository.account(subject,true); version(clean,before);
                    if (clean.containsKey("departmentId") && !clean.get("departmentId").equals(before.get("departmentId"))) activeDepartment((UUID)clean.get("departmentId"));
                    Map<String,Object> values=new LinkedHashMap<>(before); values.putAll(clean); repository.updateAccount(subject,values); repository.protectLastManager(); result=repository.account(subject,false);
                }
                case "G5-P13" -> {
                    before=repository.account(subject,true); version(clean,before); repository.bindRoles(subject,roles(clean.get("roleIds")),true); repository.protectLastManager(); result=repository.account(subject,false);
                }
                case "G5-P11" -> { subject=repository.createRole(clean); result=repository.role(subject); createdKind="ROLE"; }
                case "G5-P12" -> { before=repository.role(subject); version(clean,before); repository.updateRole(subject,clean); repository.protectLastManager(); result=repository.role(subject); }
                case "G5-P19" -> {
                    before=repository.configuration(true);version(clean,before);
                    if(!before.get("values").equals(clean.get("values"))) repository.updateConfiguration(configValues(clean),actor.userId());
                    result=repository.configuration(false);
                }
                case "G5-P16" -> { subject=repository.createDepartment(clean); result=repository.department(subject,false); createdKind="DEPARTMENT"; }
                case "G5-P17" -> { before=repository.department(subject,true); version(clean,before); Map<String,Object> values=new LinkedHashMap<>(before); values.putAll(clean); repository.updateDepartment(subject,values); result=repository.department(subject,false); }
                default -> throw new IllegalArgumentException("Undeclared management operation");
            }
            if(operation.equals("G5-P19")) {
                if(!before.get("version").equals(result.get("version"))) audit.configuration(actor.userId(),trace,before,result);
            } else audit.management(operation,actor.userId(),subject,Long.parseLong((String)result.get("version")),trace,Set.of("G5-P11","G5-P12","G5-P16","G5-P17").contains(operation),auditFields(before),auditFields(result));
            Map<String,Object> response=envelope(result,trace); int code=createdKind==null ? 200 : 201;
            repository.remember(actor.userId(),method,path,key,digest,json.writeValueAsString(response),code,createdKind,subject);
            return new Result(response,code,false);
        });
    }
    private Result replay(UUID actor,String method,String path,UUID key,String digest,UUID trace) {
        var cached=repository.cached(actor,method,path,key);
        if(cached!=null&&cached.live()) { sameDigest(digest,cached.digest()); return new Result(parse(cached.response()),cached.status(),true); }
        var intent=repository.intent(actor,method,path,key);
        if(intent==null) return null;
        sameDigest(digest,intent.digest());
        Map<String,Object> current=switch(intent.kind()) { case "ACCOUNT" -> repository.account(intent.id(),false); case "ROLE" -> repository.role(intent.id()); default -> repository.department(intent.id(),false); };
        return new Result(envelope(current,trace),200,true);
    }
    @SuppressWarnings("unchecked") private List<UUID> scopeIds(Object grants,String field) {
        return ((List<Map<String,Object>>)grants).stream().map(g->(Map<String,Object>)g.get("scope")).filter(scope->scope.containsKey(field)).map(scope->(UUID)scope.get(field)).distinct().sorted().toList();
    }
    private List<Map<String,Object>> grants(Object value) {
        if(!(value instanceof List<?> list)||list.size()>1000) throw Credentials.invalid("grants","权限须为最多 1000 项的数组");
        Map<String,Map<String,Object>> normalized=new TreeMap<>();
        for(Object item:list) {
            if(!(item instanceof Map<?,?> grant)||!grant.keySet().equals(Set.of("action","scope"))||!(grant.get("action") instanceof String action)
                    ||!(grant.get("scope") instanceof Map<?,?> scope)||!(scope.get("kind") instanceof String kind)) throw Credentials.invalid("grants","权限和范围字段不合法");
            if(!PermissionCatalog.require(action).scopeKinds().contains(kind)) throw Credentials.invalid("grants","此操作不支持所选范围");
            String field=kind.equals("ROOM") ? "roomId" : kind.equals("DEPARTMENT") ? "departmentId" : null;
            if(!scope.keySet().equals(field==null ? Set.of("kind") : Set.of("kind",field))) throw Credentials.invalid("grants","范围字段不合法");
            Map<String,Object> cleanScope=new TreeMap<>(); cleanScope.put("kind",kind);
            if(field!=null) cleanScope.put(field,id(scope.get(field),"grants"));
            Map<String,Object> cleanGrant=new TreeMap<>(); cleanGrant.put("action",action); cleanGrant.put("scope",cleanScope);
            normalized.put(action+":"+kind+":"+(field==null?"":cleanScope.get(field)),cleanGrant);
        }
        return List.copyOf(normalized.values());
    }
    private Map<String,Object> validate(String operation,Map<String,Object> input) {
        if (input==null) throw Credentials.invalid("body","请求体必填");
        Set<String> allowed=switch(operation) {
            case "G5-P07" -> Set.of("userCode","displayName","departmentId","email","initialPassword","roleIds");
            case "G5-P08" -> Set.of("expectedVersion","displayName","departmentId","email","enabled");
            case "G5-P13" -> Set.of("expectedVersion","roleIds");
            case "G5-P11" -> Set.of("key","name","grants");
            case "G5-P12" -> Set.of("expectedVersion","name","grants");
            case "G5-P16" -> Set.of("code","name");
            case "G5-P19" -> Set.of("expectedVersion","values");
            default -> Set.of("expectedVersion","name","enabled");
        };
        if (!allowed.containsAll(input.keySet())) throw Credentials.invalid("body","包含不允许修改的字段");
        Set<String> required=operation.equals("G5-P08")||operation.equals("G5-P17") ? Set.of("expectedVersion") : allowed;
        if (!input.keySet().containsAll(required) || ((operation.equals("G5-P08")||operation.equals("G5-P17"))&&input.size()<2)) throw Credentials.invalid("body","缺少必填字段或没有修改项");
        Map<String,Object> result=new LinkedHashMap<>();
        for (var entry:input.entrySet()) {
            String field=entry.getKey(); Object value=entry.getValue();
            switch(field) {
                case "grants" -> result.put(field,grants(value));
                case "values" -> result.put(field,configurationRules.validate(value));
                case "enabled" -> { if (!(value instanceof Boolean)) throw Credentials.invalid(field,"须为布尔值"); result.put(field,value); }
                case "departmentId" -> result.put(field,id(value,field));
                case "roleIds" -> {
                    if (!(value instanceof List<?> values)||values.size()>50) throw Credentials.invalid(field,"角色须为最多 50 项的数组");
                    result.put(field,values.stream().map(item->id(item,field)).distinct().sorted().toList());
                }
                case "expectedVersion" -> { if (!(value instanceof String text)||!text.matches("[1-9][0-9]{0,18}")) throw Credentials.invalid(field,"版本须为正整数字符串"); try { Long.parseLong(text); } catch(NumberFormatException invalid) { throw Credentials.invalid(field,"版本超出范围"); } result.put(field,text); }
                default -> {
                    int min=field.equals("initialPassword")?8:1; int max=field.equals("initialPassword")?24:field.equals("email")?254:field.equals("key")?60:Set.of("userCode","code").contains(field)?40:100;
                    if (!(value instanceof String text)) throw Credentials.invalid(field,"须为字符串");
                    String textValue=Credentials.text(text,min,max,field,!field.equals("initialPassword"));
                    if (field.equals("email")&&!textValue.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw Credentials.invalid(field,"邮箱格式不合法");
                    result.put(field,textValue);
                }
            }
        }
        return result;
    }
    @SuppressWarnings("unchecked") private List<UUID> roles(Object value) {
        List<UUID> roles=(List<UUID>)value;
        for(UUID role:roles) { try { repository.role(role); } catch(IdentityException missing) { if(missing.status()==404) throw Credentials.invalid("roleIds","角色不存在"); throw missing; } }
        return roles;
    }
    private void activeDepartment(UUID id) {
        try { if (!Boolean.TRUE.equals(repository.department(id,true).get("enabled"))) throw Credentials.invalid("departmentId","不能新绑定停用院系"); }
        catch(IdentityException missing) { if(missing.status()==404) throw Credentials.invalid("departmentId","院系不存在"); throw missing; }
    }
    private void version(Map<String,Object> body,Map<String,Object> current) {
        if (!body.get("expectedVersion").equals(current.get("version"))) throw new IdentityException(409,"VERSION_CONFLICT","记录已发生变化，请刷新后重试",null,(String)current.get("version"),null);
    }
    public static UUID id(Object value,String field) {
        if (!(value instanceof String text)||!text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw Credentials.invalid(field,"须为标准小写 UUID");
        return UUID.fromString(text);
    }
    private int integer(String value,int min,int max,String field) {
        try { if(!value.matches("[0-9]+")) throw new NumberFormatException(); int number=Integer.parseInt(value); if(number<min||number>max) throw new NumberFormatException(); return number; }
        catch(NumberFormatException invalid) { throw Credentials.invalid(field,"分页参数超出范围"); }
    }
    private String hmac(Map<String,Object> value) {
        String secret=environment.getProperty("G5_IDEMPOTENCY_HMAC_KEY");
        if(secret==null||secret.length()<32||secret.length()>256) throw new IdentityException(503,"AUTH_UNAVAILABLE","管理写入密钥未配置，请联系管理员");
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(json.writeValueAsString(new TreeMap<>(value)).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.GeneralSecurityException impossible) { throw new IllegalStateException("HmacSHA256 required",impossible); }
    }
    @SuppressWarnings("unchecked") private Map<String,Integer> configValues(Map<String,Object> clean) { return (Map<String,Integer>)clean.get("values"); }
    private void sameDigest(String expected,String actual) { if(!Credentials.same(expected,actual)) throw new IdentityException(409,"IDEMPOTENCY_CONFLICT","同一请求键不能用于不同内容"); }
    @SuppressWarnings("unchecked") private Map<String,Object> parse(String value) { return json.readValue(value,Map.class); }
    private Map<String,Object> envelope(Map<String,Object> value,UUID trace) { return Map.of("data",value,"traceId",trace.toString()); }
    private List<Map<String,Object>> auditFields(Map<String,Object> record) {
        List<Map<String,Object>> fields=new ArrayList<>();
        for(String name:List.of("version","enabled","departmentId","code","key","name")) if(record.containsKey(name)) fields.add(Map.of("name",name,"value",record.get(name).toString()));
        if(record.get("grants") instanceof List<?> grants) {
            fields.add(Map.of("name","grantsCount","value",Integer.toString(grants.size())));
            fields.add(Map.of("name","grantsSHA256","value",Credentials.hash(json.writeValueAsString(grants))));
        }
        if(record.get("roleIds") instanceof List<?> roles) {
            if(roles.isEmpty()) fields.add(Map.of("name","roleIds1","value","[]"));
            for(int start=0;start<roles.size();start+=10) fields.add(Map.of("name","roleIds"+(start/10+1),"value",json.writeValueAsString(roles.subList(start,Math.min(start+10,roles.size())))));
        }
        return fields;
    }
}
