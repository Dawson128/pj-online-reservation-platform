package cn.edu.campus.approval.g5.domain;

import java.util.*;

/** Closed, bounded internal request validation, shared by HTTP and same-process ports. */
public final class BusinessInput {
    public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) throw invalid();
        @SuppressWarnings("unchecked") var result=(Map<String,Object>)map;
        return result;
    }
    public static void fields(Map<String,Object> body, Set<String> required, String... optional) {
        Set<String> allowed=new HashSet<>(required);allowed.addAll(List.of(optional));
        if(body==null||!body.keySet().containsAll(required)||!allowed.containsAll(body.keySet())) throw invalid();
    }
    public static UUID id(Object value) {
        if(!(value instanceof String text)||!text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
        return UUID.fromString(text);
    }
    public static String version(Object value) {
        if(!(value instanceof String text)||!text.matches("[1-9][0-9]{0,18}")) throw invalid();
        try {Long.parseLong(text);} catch(NumberFormatException bad){throw invalid();}return text;
    }
    public static String text(Object value,int max) {
        if(!(value instanceof String text)||text.isBlank()||text.codePointCount(0,text.length())>max) throw invalid();return text;
    }
    public static int integer(Object value,int min,int max) {
        if(!(value instanceof Integer n)||n<min||n>max) throw invalid();return n;
    }
    public static List<?> list(Object value,int min,int max) {
        if(!(value instanceof List<?> list)||list.size()<min||list.size()>max) throw invalid();return list;
    }
    public static cn.edu.campus.approval.g5.api.IdentityException invalid() {return Credentials.invalid("body","请求字段、用途或值不合法");}
    private BusinessInput() { }
}
