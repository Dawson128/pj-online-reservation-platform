package cn.edu.campus.approval.g5.api;

import java.util.List;
import java.util.UUID;

/** I09 exposes only current resource-impact facts, never credentials or role/contact information. */
public record QualificationFacts(List<AccountFact> items,String checkedAt,String validUntil) {
    public QualificationFacts { items=List.copyOf(items); }
    public record AccountFact(UUID userId,UUID departmentId,boolean enabled,String accountVersion) { }
}
