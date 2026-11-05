package cn.edu.campus.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/** No positive cache, no redirect, no retry. Every accepted API request consults current G5 facts. */
public final class G5IntrospectionClient {
    private final URI endpoint;
    private final Function<String,String> credentials;
    private final HttpClient http;
    private final Duration timeout;
    private final JsonMapper json=new JsonMapper();
    public G5IntrospectionClient(URI base, boolean allowLocalDevelopmentHttp, Function<String,String> credentials) {
        this(base,allowLocalDevelopmentHttp,credentials,Duration.ofSeconds(5));
    }
    G5IntrospectionClient(URI base, boolean allowHttp, Function<String,String> credentials, Duration timeout) {
        boolean loopback=Set.of("localhost","127.0.0.1","[::1]","::1").contains(base.getHost()==null?"":base.getHost());
        if(base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null
                || !(base.getPath().isEmpty()||base.getPath().equals("/"))
                || !("https".equals(base.getScheme())||"http".equals(base.getScheme())&&(allowHttp||loopback)))
            throw new IllegalArgumentException("G5 base URL must use HTTPS; HTTP requires loopback or an explicit development opt-in");
        endpoint=base.resolve("/internal/auth/introspect");this.credentials=credentials;this.timeout=timeout;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public CampusPrincipal authenticate(String module,String sessionToken,String method,String csrfToken) {
        if(sessionToken==null||sessionToken.length()<32||sessionToken.length()>256||sessionToken.chars().anyMatch(Character::isISOControl))throw AuthFailure.unauthenticated();
        String credential=credentials.apply(module);
        if(credential==null||credential.length()<32||credential.length()>256||credential.chars().anyMatch(Character::isISOControl))throw AuthFailure.unavailable();
        try {
            Map<String,Object> body=new LinkedHashMap<>();body.put("sessionToken",sessionToken);body.put("requestMethod",method);
            if(!Set.of("GET","HEAD","OPTIONS").contains(method)&&csrfToken!=null)body.put("csrfToken",csrfToken);
            HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization","Bearer "+credential).header("Accept","application/json").header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<byte[]> response=http.send(request,info->new LimitedBody());
            var envelope=json.readTree(response.body());
            if(response.statusCode()==401 && "UNAUTHENTICATED".equals(envelope.path("code").asText()))throw AuthFailure.unauthenticated();
            if(response.statusCode()==403 && "FORBIDDEN".equals(envelope.path("code").asText())) {
                for(var field:envelope.path("details").path("fields"))if("csrfToken".equals(field.path("field").asText()))
                    throw new AuthFailure(403,"FORBIDDEN","安全校验失败，请重新登录后重试");
            }
            if(response.statusCode()!=200)throw AuthFailure.unavailable();
            CampusPrincipal.exact(envelope,Set.of("data","traceId"));CampusPrincipal.uuid(envelope.path("traceId"));
            CampusPrincipal principal=CampusPrincipal.parse(envelope.path("data"));
            if(principal.mustChangePassword())throw new AuthFailure(403,"PASSWORD_CHANGE_REQUIRED","请先完成密码修改");
            return principal;
        } catch(InterruptedException failure) {Thread.currentThread().interrupt();throw AuthFailure.unavailable();}
        catch(AuthFailure failure) {throw failure;}
        catch(IOException|RuntimeException failure) {throw AuthFailure.unavailable();}
    }
    /** Cancel oversized replies while streaming, before buffering the full provider response. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        @Override public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        @Override public void onSubscribe(Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
        @Override public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer buffer:buffers)size+=buffer.remaining();
            if(size>524288){subscription.cancel();delegate.onError(new IOException("Authentication reply exceeds size limit"));}
            else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable failure){delegate.onError(failure);}
        @Override public void onComplete(){delegate.onComplete();}
    }
}
