package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cauce.api.observability.OperationalGauges;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The operational gauges read the whole instance through V53's aggregate functions, as the runtime
 * role and without a tenant context (ADR 0011, M2): a message waiting in the queue and its audit
 * event waiting for the chain show up, whoever's they are.
 */
@TestPropertySource(properties = {
        "cauce.observability.gauges.enabled=true",
        "cauce.observability.gauges.interval-ms=3600000"
})
class OperationalGaugesIT extends AbstractBusinessApiIT {

    @Autowired
    private OperationalGauges gauges;

    @Autowired
    private MeterRegistry registry;

    @Test
    void refresh_seesAWaitingInvocationAndItsUnchainedAuditEvent_withoutATenantContext() throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, List.of());
        mockMvc.perform(post("/v1/agents/" + agentId + "/messages").header(HttpHeaders.AUTHORIZATION, clientAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("external_identity_ref", "persona-1",
                                "content", "hola"))))
                .andExpect(status().isAccepted());

        gauges.refresh();

        assertThat(gauge("cauce.queue.pending")).isEqualTo(1.0);
        assertThat(gauge("cauce.queue.oldest_due.age")).isGreaterThanOrEqualTo(0.0);
        assertThat(gauge("cauce.audit.outbox.pending")).isGreaterThan(0.0);
        assertThat(registry.get("cauce.spend.alerts").tag("threshold", "100").gauge().value()).isZero();
    }

    @Test
    void refresh_anEmptyInstance_readsZero() {
        gauges.refresh();

        assertThat(gauge("cauce.queue.pending")).isZero();
        assertThat(gauge("cauce.queue.oldest_due.age")).isZero();
        assertThat(gauge("cauce.owner.notices.unsent")).isZero();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
