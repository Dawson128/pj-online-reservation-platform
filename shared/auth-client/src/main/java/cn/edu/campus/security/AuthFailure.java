package cn.edu.campus.security;

/** Public, deliberately credential-free failures. Never include upstream request/response bodies. */
public final class AuthFailure extends RuntimeException {
    private final int status;
    private final String code;
    public AuthFailure(int status, String code, String message) {
        super(message); this.status=status; this.code=code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static AuthFailure unauthenticated() { return new AuthFailure(401,"UNAUTHENTICATED","登录已失效，请重新登录"); }
    public static AuthFailure unavailable() { return new AuthFailure(503,"AUTH_UNAVAILABLE","暂时无法核验身份，请稍后重试"); }
}
