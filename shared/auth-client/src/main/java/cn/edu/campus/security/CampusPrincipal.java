package cn.edu.campus.security;

import java.time.OffsetDateTime;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Immutable consumer projection of G5 Principal, not a second identity/authorization store. */
public record CampusPrincipal(UUID userId, String displayName, UUID deptId, String accountVersion,
        String permissionVersion, List<Grant> grants, boolean mustChangePassword, String expiresAt)
        implements java.security.Principal {
    public record Grant(String action, Map<String,Object> scope) {
        public Grant { scope=Map.copyOf(scope); }
    }
    public CampusPrincipal { grants=List.copyOf(grants); }
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Override public String getName() { return userId.toString(); }

    static CampusPrincipal parse(JsonNode node) {
        exact(node,Set.of("userId","displayName","deptId","accountVersion","permissionVersion","grants","mustChangePassword","expiresAt"));
        if (!node.path("grants").isArray() || !node.path("mustChangePassword").isBoolean()) throw AuthFailure.unavailable();
        String expiry=text(node.path("expiresAt")); OffsetDateTime.parse(expiry);
        List<Grant> grants=new ArrayList<>();
        for (JsonNode grant:node.path("grants")) {
            exact(grant,Set.of("action","scope"));
            JsonNode scope=grant.path("scope"); String kind=text(scope.path("kind"));
            Map<String,Object> values;
            switch(kind) {
                case "SYSTEM", "SCHOOL" -> { exact(scope,Set.of("kind")); values=Map.of("kind",kind); }
                case "ROOM" -> { exact(scope,Set.of("kind","roomId")); values=Map.of("kind",kind,"roomId",uuid(scope.path("roomId")).toString()); }
                case "DEPARTMENT" -> { exact(scope,Set.of("kind","departmentId")); values=Map.of("kind",kind,"departmentId",uuid(scope.path("departmentId")).toString()); }
                default -> throw AuthFailure.unavailable();
            }
            grants.add(new Grant(text(grant.path("action")),values));
        }
        return new CampusPrincipal(uuid(node.path("userId")),text(node.path("displayName")),uuid(node.path("deptId")),
                version(node.path("accountVersion")),version(node.path("permissionVersion")),grants,
                node.path("mustChangePassword").asBoolean(),expiry);
    }
    static void exact(JsonNode node,Set<String> names) { if(!node.isObject()||!node.propertyNames().equals(names))throw AuthFailure.unavailable(); }
    static String text(JsonNode node) { if(!node.isString()||node.asText().isBlank())throw AuthFailure.unavailable();return node.asText(); }
    static UUID uuid(JsonNode node) {
        String value=text(node);
        if(!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw AuthFailure.unavailable();
        return UUID.fromString(value);
    }
    private static String version(JsonNode node) {
        String value=text(node);
        if(!value.matches("[1-9][0-9]{0,18}")||Long.parseLong(value)<=0)throw AuthFailure.unavailable();
        return value;
    }
}
