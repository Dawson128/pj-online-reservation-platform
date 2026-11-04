package cn.edu.campus.approval.g5.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Validates this contract's closed object of bounded integers; not a general JSON Schema interpreter. */
@org.springframework.context.annotation.Profile("!shell")
@Component
public final class ConfigurationRules {
    private final Map<String,Map<String,Object>> fields;
    @SuppressWarnings("unchecked") public ConfigurationRules(ObjectMapper json) {
        try(InputStream input=ConfigurationRules.class.getResourceAsStream("/g5/config-values.schema.json")) {
            if(input==null) throw new IllegalStateException("Build the composed configuration schema first");
            fields=(Map<String,Map<String,Object>>)json.readValue(input,Map.class).get("properties");
        } catch(IOException failure) { throw new IllegalStateException("Cannot read composed configuration contract",failure); }
    }
    public Map<String,Integer> validate(Object value) {
        if(!(value instanceof Map<?,?> values)||!values.keySet().equals(fields.keySet())) throw Credentials.invalid("values","必须完整提供五项参数，不能包含其他规则");
        Map<String,Integer> clean=new TreeMap<>();
        for(var entry:fields.entrySet()) {
            String key=entry.getKey();var rule=entry.getValue();Object raw=values.get(key);
            int number;
            try {
                if(!(raw instanceof Number)) throw new ArithmeticException();
                number=new java.math.BigDecimal(raw.toString()).intValueExact();
                if(number<((Number)rule.get("minimum")).intValue()||number>((Number)rule.get("maximum")).intValue()) throw new ArithmeticException();
            } catch(ArithmeticException|NumberFormatException invalid) { throw Credentials.invalid("values."+key,rule.get("title")+"须为 "+rule.get("minimum")+"—"+rule.get("maximum")+" 的整数"); }
            clean.put(key,number);
        }
        return clean;
    }

    /** Maven process-classes: compose baseline fragments and reject divergence from the reviewed document. */
    @SuppressWarnings("unchecked") public static void main(String[] args) throws IOException {
        Path folder=Path.of(args[0]),document=Path.of(args[1]),output=Path.of(args[2]);
        var json=new JsonMapper();
        Map<String,Object> committed=json.readValue(Files.readString(folder.resolve("config-values.schema.json")),Map.class);
        Map<String,Object> merged=new TreeMap<>();
        String technical=Files.readString(document,StandardCharsets.UTF_8);
        Map<String,String> owners=Map.of("reservationMaxHours","G1","reservationAdvanceDays","G1","applicationAdvanceDays","G2","scanIntervalSeconds","G6","notificationRetryLimit","G6");
        for(String group:List.of("G1","G2","G6")) {
            Map<String,Object> fragment=json.readValue(Files.readString(folder.resolve("baseline/"+group.toLowerCase()+".schema.json")),Map.class);
            if(!Boolean.FALSE.equals(fragment.get("additionalProperties"))||!"object".equals(fragment.get("type"))||!group.equals(fragment.get("x-owner-group"))) throw new IllegalStateException("Invalid owner fragment: "+group);
            Map<String,Object> properties=(Map<String,Object>)fragment.get("properties");
            if(!Set.copyOf((List<String>)fragment.get("required")).equals(properties.keySet())) throw new IllegalStateException("All configuration fields are required");
            for(var entry:properties.entrySet()) {
                String key=entry.getKey();Map<String,Object> rule=(Map<String,Object>)entry.getValue();
                if(!group.equals(owners.get(key))||merged.putIfAbsent(key,rule)!=null||!"integer".equals(rule.get("type"))||!group.equals(rule.get("x-owner-group"))) throw new IllegalStateException("Unknown, duplicate or misowned config key");
                if(!Set.of("type","minimum","maximum","default","title","description","x-unit","x-owner-group").equals(rule.keySet())) throw new IllegalStateException("Unsupported config field schema");
                var bounds=Pattern.compile(Pattern.quote(key)+":Int\\[([0-9]+),([0-9]+)\\]").matcher(technical);
                if(!bounds.find()||!Integer.valueOf(bounds.group(1)).equals(rule.get("minimum"))||!Integer.valueOf(bounds.group(2)).equals(rule.get("maximum"))) throw new IllegalStateException("Bounds differ from technical baseline: "+key);
                var defaults=Pattern.compile("默认values=\\{([^}]+)\\}").matcher(technical);
                if(!defaults.find()||!Pattern.compile(Pattern.quote(key)+":"+rule.get("default")+"(?:,|$)").matcher(defaults.group(1)).find()) throw new IllegalStateException("Default differs from technical baseline: "+key);
            }
        }
        if(!merged.keySet().equals(owners.keySet())||!merged.equals(committed.get("properties"))||!Boolean.FALSE.equals(committed.get("additionalProperties"))
                ||!Set.copyOf((List<String>)committed.get("required")).equals(owners.keySet())||!"object".equals(committed.get("type"))) throw new IllegalStateException("Composed schema differs from owner fragments");
        Files.createDirectories(output.getParent());Files.writeString(output,json.writeValueAsString(committed),StandardCharsets.UTF_8);
        System.out.println("G5 configuration contract composed: five bounded integer parameters; owner review remains pending.");
    }
}
