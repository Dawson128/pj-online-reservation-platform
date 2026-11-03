package cn.edu.campus.approval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("shell")
class BootstrapBoundaryTest {
    @Autowired MockMvc mvc;
    @Test void livenessCanBeCheckedWithoutExposingBusiness() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
    @Test void businessAndInternalEndpointsRemainClosedUntilAuthIsIntegrated() throws Exception {
        for (String path : new String[]{"/api/m2/not-yet-implemented", "/internal/events", "/actuator/env", "/actuator/health"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }
}
