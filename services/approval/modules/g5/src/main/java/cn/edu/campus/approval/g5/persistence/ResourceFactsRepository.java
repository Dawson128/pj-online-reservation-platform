package cn.edu.campus.approval.g5.persistence;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import cn.edu.campus.approval.g5.api.QualificationFacts.AccountFact;

@org.springframework.context.annotation.Profile("!shell")
@Repository
public class ResourceFactsRepository {
    private final JdbcTemplate jdbc;
    public ResourceFactsRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Map<UUID,AccountFact> accounts(List<UUID> input) {
        List<UUID> ids=input.stream().distinct().toList();
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        // One MVCC read of explicitly minimized columns; no per-account reads or credential material.
        List<AccountFact> rows=jdbc.query("SELECT id,department_id,enabled,version FROM m2.account WHERE id IN ("+placeholders+")",
                (row,index)->new AccountFact(row.getObject("id",UUID.class),row.getObject("department_id",UUID.class),row.getBoolean("enabled"),Long.toString(row.getLong("version"))),ids.toArray());
        return rows.stream().collect(Collectors.toMap(AccountFact::userId,Function.identity()));
    }
    public OffsetDateTime checkedAt() { return jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class); }
}
