package cn.edu.campus.approval.g5.api;

/** G5-L03 for the G2 in-process consumer. No stale/default fallback or cross-request positive cache. */
public interface ConfigurationPort {
    BusinessConfiguration current();
}
