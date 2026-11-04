package cn.edu.campus.approval.g5.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import cn.edu.campus.approval.g5.api.IdentityException;

@org.springframework.context.annotation.Profile("!shell")
@Component
public final class Credentials {
    private final SecureRandom random = new SecureRandom();
    private final PasswordEncoder encoder = new DelegatingPasswordEncoder("pbkdf2@SpringSecurity_v5_8",
            Map.of("pbkdf2@SpringSecurity_v5_8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    private final String dummyHash = encoder.encode(token());

    public String passwordHash(String password) { return encoder.encode(password); }
    public boolean matches(String password, String hash) {
        return encoder.matches(password, hash == null ? dummyHash : hash);
    }
    public String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime");
        }
    }
    public static boolean same(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }
    public static String text(String value, int min, int max, String field, boolean strip) {
        if (value == null) {
            throw invalid(field, "此字段必填");
        }
        String result = strip ? value.strip() : value;
        int length = result.codePointCount(0, result.length());
        if (length < min || length > max || result.codePoints().anyMatch(Character::isISOControl)
                || result.chars().anyMatch(c -> Character.isSurrogate((char) c))
                    && !wellFormed(result)) {
            throw invalid(field, "长度须为 " + min + "—" + max + " 个字符，且不能包含控制字符");
        }
        return result;
    }
    private static boolean wellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) { return false; }
            } else if (Character.isLowSurrogate(current)) { return false; }
        }
        return true;
    }
    public static IdentityException invalid(String field, String message) {
        return new IdentityException(400, "VALIDATION_ERROR", message, field, null, null);
    }
    public static IdentityException unauthenticated() {
        return new IdentityException(401, "UNAUTHENTICATED", "登录凭据无效，请重新登录");
    }
}
