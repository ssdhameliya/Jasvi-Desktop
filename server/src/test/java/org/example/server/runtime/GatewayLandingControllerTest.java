package org.example.server.runtime;

import org.example.shared.RuntimeContract;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GatewayLandingControllerTest {

    @Test
    void rendersSanitizedWorkstationGatewayDashboardWithoutDataLeaks() throws Exception {
        RuntimeService service = mock(RuntimeService.class);
        when(service.databaseReady()).thenReturn(true);
        when(service.databaseName()).thenReturn("dse_erp_test");

        MockMvc mvc = standaloneSetup(new GatewayLandingController(service, RuntimeContract.appVersion(), "LOCAL")).build();

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Enterprise Workstation Remote Gateway")))
                .andExpect(content().string(containsString("Ready for Workstation Connections")))
                .andExpect(content().string(not(containsString("dse_erp_test"))))
                .andExpect(content().string(not(containsString("58080"))))
                .andExpect(content().string(not(containsString("jasviindustries.in"))));

        mvc.perform(get("/gateway"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Enterprise Workstation Remote Gateway")));
    }
}
