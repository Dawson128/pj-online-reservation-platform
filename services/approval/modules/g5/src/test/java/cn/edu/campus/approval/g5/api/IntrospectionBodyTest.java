package cn.edu.campus.approval.g5.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class IntrospectionBodyTest {
    @Test void optionalFieldsPreserveMissingVsNullAndDoNotExposeCredentials() {
        var body=new JsonMapper().readValue("{\"sessionToken\":\"private-session\",\"csrfToken\":null}",AuthController.IntrospectionBody.class);
        assertFalse(body.containsKey("requestMethod"));
        assertTrue(body.containsKey("csrfToken"));assertNull(body.get("csrfToken"));
        assertFalse(body.toString().contains("private-session"));
        body.put("csrfToken","private-csrf");assertFalse(body.toString().contains("private-csrf"));
    }
}
