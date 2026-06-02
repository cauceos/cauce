package dev.cauce.api.tenant;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.security.ApiKeyAuthenticationFilter;
import dev.cauce.api.web.RequestIdFilter;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.core.tenant.Tier;
import dev.cauce.tenancy.TenantService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link TenantController}: HTTP contract and validation with the service
 * mocked. The custom servlet filters are excluded from the slice (they need beans outside it),
 * and {@code addFilters = false} keeps the request off the security chain.
 */
@WebMvcTest(controllers = TenantController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {RequestIdFilter.class, ApiKeyAuthenticationFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class TenantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TenantService tenantService;

    private static Tenant tenant(UUID id, UUID parent, Tier tier, String name) {
        Instant now = Instant.now();
        return Tenant.rehydrate(id, parent, tier, name, now, now);
    }

    @Test
    void createPartner_valid_returns201WithLocationAndBody() throws Exception {
        UUID operatorId = UUID.randomUUID();
        UUID partnerId = UUID.randomUUID();
        given(tenantService.createPartner(eq("Partner Co"), eq(operatorId)))
                .willReturn(tenant(partnerId, operatorId, Tier.PARTNER, "Partner Co"));

        mockMvc.perform(post("/v1/tenants/partner").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePartnerRequest("Partner Co", operatorId))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/v1/tenants/" + partnerId))
                .andExpect(jsonPath("$.id").value(partnerId.toString()))
                .andExpect(jsonPath("$.tier").value("PARTNER"))
                .andExpect(jsonPath("$.parent_tenant_id").value(operatorId.toString()));
    }

    @Test
    void createPartner_blankName_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/v1/tenants/partner").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePartnerRequest("  ", UUID.randomUUID()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void createClient_valid_returns201() throws Exception {
        UUID partnerId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        given(tenantService.createClient(eq("Client Co"), eq(partnerId)))
                .willReturn(tenant(clientId, partnerId, Tier.CLIENT, "Client Co"));

        mockMvc.perform(post("/v1/tenants/client").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateClientRequest("Client Co", partnerId))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/v1/tenants/" + clientId))
                .andExpect(jsonPath("$.tier").value("CLIENT"));
    }

    @Test
    void getTenant_existing_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        given(tenantService.getTenant(id)).willReturn(tenant(id, null, Tier.OPERATOR, "Op"));

        mockMvc.perform(get("/v1/tenants/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.tier").value("OPERATOR"))
                .andExpect(jsonPath("$.parent_tenant_id").value(nullValue()));
    }

    @Test
    void getTenant_notFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        given(tenantService.getTenant(id)).willThrow(new TenantNotFoundException("nope"));

        mockMvc.perform(get("/v1/tenants/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("tenant_not_found"));
    }

    @Test
    void listChildren_returns200List() throws Exception {
        UUID parent = UUID.randomUUID();
        given(tenantService.listChildren(parent)).willReturn(List.of(
                tenant(UUID.randomUUID(), parent, Tier.CLIENT, "C1"),
                tenant(UUID.randomUUID(), parent, Tier.CLIENT, "C2")));

        mockMvc.perform(get("/v1/tenants/" + parent + "/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tier").value("CLIENT"));
    }
}
