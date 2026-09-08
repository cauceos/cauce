package dev.cauce.api.invocation;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cauce.api.security.ApiKeyAuthenticationFilter;
import dev.cauce.api.web.RequestIdFilter;
import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.PendingInvocationService;
import dev.cauce.orchestration.PendingInvocationStatus;
import dev.cauce.orchestration.events.InvocationFailureType;
import dev.cauce.orchestration.usage.LlmUsageQueryService;
import dev.cauce.orchestration.usage.LlmUsageRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link InvocationController}: the public status/failure vocabulary
 * mapping, the 404 contract, and — critically — that the stored error detail (which may
 * carry raw provider messages) never appears anywhere in the response body.
 */
@WebMvcTest(controllers = InvocationController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {RequestIdFilter.class, ApiKeyAuthenticationFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class InvocationControllerTest {

    private static final String PROVIDER_DETAIL = "LlmAuthenticationException: 401 invalid x-api-key";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PendingInvocationService pendingInvocationService;

    // The controller composes the invocation row with its usage. Mockito answers an empty
    // list by default, which is the "nothing recorded" path — the case each existing test
    // above already represents.
    @MockitoBean
    private LlmUsageQueryService usageQueryService;

    @Test
    void getInvocation_pending_returns200WithStatusPending() throws Exception {
        PendingInvocation invocation = pendingInvocation();
        given(pendingInvocationService.getPendingInvocation(invocation.id()))
                .willReturn(Optional.of(invocation));

        mockMvc.perform(get("/v1/invocations/" + invocation.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(invocation.id().toString()))
                .andExpect(jsonPath("$.conversation_id").value(invocation.conversationId().toString()))
                .andExpect(jsonPath("$.trigger_message_id")
                        .value(invocation.triggerMessageId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.failure_reason").value((Object) null))
                .andExpect(jsonPath("$.created_at").exists())
                .andExpect(jsonPath("$.completed_at").value((Object) null));
    }

    @Test
    void getInvocation_processing_returnsStatusProcessing() throws Exception {
        PendingInvocation invocation = pendingInvocation().claim("worker-1");
        given(pendingInvocationService.getPendingInvocation(invocation.id()))
                .willReturn(Optional.of(invocation));

        mockMvc.perform(get("/v1/invocations/" + invocation.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.failure_reason").value((Object) null));
    }

    @Test
    void getInvocation_completed_returnsCompletedWithCompletedAt() throws Exception {
        PendingInvocation invocation = pendingInvocation().claim("worker-1").complete();
        given(pendingInvocationService.getPendingInvocation(invocation.id()))
                .willReturn(Optional.of(invocation));

        mockMvc.perform(get("/v1/invocations/" + invocation.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.failure_reason").value((Object) null))
                .andExpect(jsonPath("$.completed_at").exists());
    }

    @Test
    void getInvocation_failedWithLlmError_returnsProviderErrorWithoutProviderDetail() throws Exception {
        PendingInvocation invocation = pendingInvocation().claim("worker-1")
                .fail(PROVIDER_DETAIL, InvocationFailureType.LLM_ERROR);
        given(pendingInvocationService.getPendingInvocation(invocation.id()))
                .willReturn(Optional.of(invocation));

        String body = mockMvc.perform(get("/v1/invocations/" + invocation.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure_reason").value("PROVIDER_ERROR"))
                .andExpect(jsonPath("$.last_error").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        Assertions.assertThat(body)
                .as("the raw provider error string must never reach the client")
                .doesNotContain("401").doesNotContain("x-api-key");
    }

    @Test
    void getInvocation_abandonedByReaper_returnsStatusFailedWithReasonTimeout() throws Exception {
        PendingInvocation invocation = pendingInvocation().claim("worker-1")
                .abandon("reaped (claim timeout)", InvocationFailureType.REAPER_ABANDONED);
        given(pendingInvocationService.getPendingInvocation(invocation.id()))
                .willReturn(Optional.of(invocation));

        mockMvc.perform(get("/v1/invocations/" + invocation.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure_reason").value("TIMEOUT"));
    }

    @Test
    void getInvocation_failedLegacyRowWithoutType_returnsFailedWithNullFailureReason() throws Exception {
        // Rows that failed before V17 persisted the taxonomy carry no failure_type.
        UUID id = UUID.randomUUID();
        PendingInvocation legacy = PendingInvocation.rehydrate(id, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), PendingInvocationStatus.FAILED, 1, 3,
                Instant.now(), "boom", null, Instant.now(), Instant.now(), "worker-1",
                Instant.now(), null);
        given(pendingInvocationService.getPendingInvocation(id)).willReturn(Optional.of(legacy));

        mockMvc.perform(get("/v1/invocations/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure_reason").value((Object) null));
    }

    @Test
    void getInvocation_missing_returns404WithInvocationNotFoundCode() throws Exception {
        UUID id = UUID.randomUUID();
        given(pendingInvocationService.getPendingInvocation(id)).willReturn(Optional.empty());

        mockMvc.perform(get("/v1/invocations/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("invocation_not_found"));
    }

    @Test
    void getInvocation_malformedUuid_returns400InvalidPathParameter() throws Exception {
        mockMvc.perform(get("/v1/invocations/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_path_parameter"));
    }

    @Test
    void getInvocation_abandonedAfterRetriesExhausted_returnsReasonProviderUnavailable() throws Exception {
        PendingInvocation invocation = pendingInvocation().claim("worker-1")
                .abandon("429 too many requests", InvocationFailureType.LLM_RETRIES_EXHAUSTED);
        given(pendingInvocationService.getPendingInvocation(invocation.id()))
                .willReturn(Optional.of(invocation));

        mockMvc.perform(get("/v1/invocations/" + invocation.id()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure_reason").value("PROVIDER_UNAVAILABLE"));
    }

    @Test
    void getInvocation_withNoUsageRecorded_omitsUsageAsNullNotZero() throws Exception {
        PendingInvocation invocation = pendingInvocation().claim("worker-1")
                .fail(PROVIDER_DETAIL, InvocationFailureType.LLM_ERROR);
        given(pendingInvocationService.getPendingInvocation(invocation.id()))
                .willReturn(Optional.of(invocation));
        given(usageQueryService.findByInvocation(invocation.id())).willReturn(List.of());

        // A round that failed on the way out recorded nothing. That is not "zero tokens":
        // it is no record, and the response must let a client tell the two apart.
        mockMvc.perform(get("/v1/invocations/" + invocation.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage").value((Object) null));
    }

    @Test
    void getInvocation_withUsage_exposesAggregateAndBreakdownInSnakeCase() throws Exception {
        PendingInvocation invocation = pendingInvocation().claim("worker-1").complete();
        given(pendingInvocationService.getPendingInvocation(invocation.id()))
                .willReturn(Optional.of(invocation));
        given(usageQueryService.findByInvocation(invocation.id()))
                .willReturn(List.of(usageRecord(0, 10, 4), usageRecord(1, 20, 6)));

        mockMvc.perform(get("/v1/invocations/" + invocation.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.input_tokens").value(30))
                .andExpect(jsonPath("$.usage.output_tokens").value(10))
                .andExpect(jsonPath("$.usage.total_tokens").value(40))
                .andExpect(jsonPath("$.usage.complete").value(true))
                .andExpect(jsonPath("$.usage.calls.length()").value(2))
                .andExpect(jsonPath("$.usage.calls[0].round_index").value(0))
                .andExpect(jsonPath("$.usage.calls[0].total_tokens").value(14))
                .andExpect(jsonPath("$.usage.calls[1].round_index").value(1))
                .andExpect(jsonPath("$.usage.calls[1].provider").value("anthropic"))
                .andExpect(jsonPath("$.usage.calls[1].finish_reason").value("STOP"));
    }

    @Test
    void getInvocation_failedAfterPartialUsage_marksTheTotalsIncomplete() throws Exception {
        PendingInvocation invocation = pendingInvocation().claim("worker-1")
                .fail(PROVIDER_DETAIL, InvocationFailureType.LLM_ERROR);
        given(pendingInvocationService.getPendingInvocation(invocation.id()))
                .willReturn(Optional.of(invocation));
        given(usageQueryService.findByInvocation(invocation.id()))
                .willReturn(List.of(usageRecord(0, 10, 4)));

        mockMvc.perform(get("/v1/invocations/" + invocation.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.total_tokens").value(14))
                .andExpect(jsonPath("$.usage.complete").value(false));
    }

    @Test
    void getInvocation_usageNeverCarriesACostField() throws Exception {
        PendingInvocation invocation = pendingInvocation().claim("worker-1").complete();
        given(pendingInvocationService.getPendingInvocation(invocation.id()))
                .willReturn(Optional.of(invocation));
        given(usageQueryService.findByInvocation(invocation.id()))
                .willReturn(List.of(usageRecord(0, 10, 4)));

        // Tokens are facts the ledger holds; money is not, and must not appear here by
        // any name — a monetary figure invented server-side is what someone later bills.
        String body = mockMvc.perform(get("/v1/invocations/" + invocation.id()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Assertions.assertThat(body.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("cost").doesNotContain("price").doesNotContain("eur")
                .doesNotContain("usd").doesNotContain("amount");
    }

    private static LlmUsageRecord usageRecord(int roundIndex, int inputTokens, int outputTokens) {
        return new LlmUsageRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "anthropic", "claude-sonnet-4-7",
                roundIndex, inputTokens, outputTokens, inputTokens + outputTokens, "STOP",
                Instant.now());
    }

    private PendingInvocation pendingInvocation() {
        return PendingInvocation.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
