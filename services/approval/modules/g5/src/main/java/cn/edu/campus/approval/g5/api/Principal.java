package cn.edu.campus.approval.g5.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Principal(UUID userId, String displayName, UUID deptId, String accountVersion,
                        String permissionVersion, List<Grant> grants,
                        boolean mustChangePassword, String expiresAt) {
    public record Grant(String action, Map<String, Object> scope) { }
}
