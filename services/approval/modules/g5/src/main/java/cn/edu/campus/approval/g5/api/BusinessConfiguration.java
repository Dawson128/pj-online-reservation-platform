package cn.edu.campus.approval.g5.api;

import java.util.Map;

/** An immutable current snapshot. Consumers must persist their own accepted object's rule snapshot. */
public record BusinessConfiguration(String version,String effectiveAt,Map<String,Integer> values) {
    public BusinessConfiguration { values=Map.copyOf(values); }
}
