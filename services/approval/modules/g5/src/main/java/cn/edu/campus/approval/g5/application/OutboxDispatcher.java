package cn.edu.campus.approval.g5.application;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import tools.jackson.databind.ObjectMapper;
import cn.edu.campus.approval.g5.persistence.DeliveryRepository;
import cn.edu.campus.approval.g5.persistence.DeliveryRepository.Claim;

/** Bounded G5 outbox dispatcher. No event/log recursion, network transactions, redirects, or new event IDs. */
@org.springframework.context.annotation.Profile("!shell")
@Component
public class OutboxDispatcher implements SmartLifecycle {
    private final DeliveryRepository repository;private final ConfigurationService configuration;private final Environment env;private final ObjectMapper json;
    private final TransactionTemplate tx;private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicBoolean running=new AtomicBoolean(),scanning=new AtomicBoolean();private ScheduledExecutorService scheduler;private long nextScan;
    public OutboxDispatcher(DeliveryRepository repository,ConfigurationService configuration,Environment env,ObjectMapper json,TransactionTemplate transaction){this.repository=repository;this.configuration=configuration;this.env=env;this.json=json;tx=new TransactionTemplate(transaction.getTransactionManager());tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
    @Override public void start(){
        if(!env.getProperty("G5_OUTBOX_ENABLED",Boolean.class,true)||!running.compareAndSet(false,true))return;
        scheduler=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"g5-outbox");t.setDaemon(true);return t;});
        scheduler.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
    }
    @Override public void stop(){running.set(false);if(scheduler!=null)scheduler.shutdownNow();}
    @Override public boolean isRunning(){return running.get();}
    private void tick(){
        if(!running.get()||System.nanoTime()<nextScan||!scanning.compareAndSet(false,true))return;
        try{
            int seconds=configuration.current().values().get("scanIntervalSeconds");nextScan=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
            long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();tx.executeWithoutResult(s->repository.overdue());
            URI base=origin();String secret=env.getProperty("G6_SERVICE_G5_TOKEN");
            if(base==null||secret==null||secret.length()<32||secret.length()>256){tx.executeWithoutResult(s->repository.unconfigured());return;}
            for(int n=0;n<50&&running.get()&&System.nanoTime()<deadline-Duration.ofSeconds(5).toNanos();n++){
                Claim claim=tx.execute(s->repository.claim());if(claim==null)break;send(base,secret,claim);
            }
        }catch(RuntimeException unavailable){nextScan=System.nanoTime()+Duration.ofSeconds(5).toNanos();/* No payload, credential or recursive outbox failure audit. */}
        finally{scanning.set(false);}
    }
    private URI origin(){
        try{String value=env.getProperty("G6_BASE_URL");if(value==null)return null;URI base=URI.create(value);if(!Set.of("http","https").contains(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!Set.of("","/").contains(base.getPath()))return null;return base;}catch(RuntimeException invalid){return null;}
    }
    private void send(URI base,String secret,Claim claim){
        RoomScopeClient.outsideTransaction();ScheduledFuture<?> beat=scheduler.scheduleAtFixedRate(()->{try{tx.executeWithoutResult(s->repository.heartbeat(claim));}catch(RuntimeException ignored){}},5,5,TimeUnit.SECONDS);
        String code="DEPENDENCY_UNAVAILABLE";boolean permanent=false;Boolean duplicate=null;OffsetDateTime accepted=null;
        try{
            var request=HttpRequest.newBuilder(base.resolve("/internal/events")).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+secret).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(claim.payload())).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==200){var envelope=json.readTree(response.body());var data=envelope.path("data");
                if(!data.isObject()||!data.path("eventId").isString()||!claim.id().toString().equals(data.path("eventId").asText())||!data.path("duplicate").isBoolean()||!data.path("acceptedAt").isString()||!envelope.path("traceId").isString())throw new IllegalArgumentException("Invalid receipt");
                UUID.fromString(envelope.path("traceId").asText());accepted=OffsetDateTime.parse(data.path("acceptedAt").asText());duplicate=data.path("duplicate").asBoolean();code=null;
            }else if(Set.of(400,403,409,422).contains(response.statusCode())){code="REMOTE_REJECTED";permanent=true;}
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
        catch(Exception unknown){code="DEPENDENCY_UNAVAILABLE";accepted=null;duplicate=null;}
        finally{beat.cancel(false);}
        String error=code;boolean blocked=permanent;Boolean wasDuplicate=duplicate;OffsetDateTime at=accepted;
        tx.executeWithoutResult(s->repository.finish(claim,error,blocked,wasDuplicate,at));
    }
}
