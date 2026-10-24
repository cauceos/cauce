package dev.cauce.orchestration.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.PromptCacheRetention;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.usage.SpendAlertNotifier;
import dev.cauce.core.usage.SpendAlertNotifier.SpendAlert;
import dev.cauce.orchestration.audit.UsageAuditEvents;
import dev.cauce.orchestration.persistence.SpendLimitMapper;
import dev.cauce.orchestration.persistence.SpendLimitRepository;
import dev.cauce.orchestration.usage.SpendGuard.Verdict;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

class SpendGuardTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private final SpendLimitRepository repository = Mockito.mock(SpendLimitRepository.class);
    private final AuditEventRecorder auditRecorder = Mockito.mock(AuditEventRecorder.class);
    private final SpendAlertNotifier notifier = Mockito.mock(SpendAlertNotifier.class);
    private final SpendLimitMapper mapper = new SpendLimitMapper();
    private SpendGuard guard;

    private final UUID clientId = UUID.randomUUID();
    private final Agent agent = Agent.create(clientId, "Camarero", "Eres el camarero.", "anthropic",
            "claude-sonnet-5-5");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<SpendAlertNotifier> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(notifier);
        guard = new SpendGuard(repository, mapper, auditRecorder, provider, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.hasPriceInForce("anthropic", "claude-sonnet-5-5", false)).thenReturn(true);
        when(repository.recordAlertOnce(any(), any(), any(), anyInt(), any(), any())).thenReturn(1);
    }

    @Test
    void withoutALimit_everythingRuns_andNothingElseIsRead() {
        when(repository.findByTenantId(clientId)).thenReturn(Optional.empty());

        assertThat(guard.check(agent)).isEqualTo(Verdict.ALLOWED);

        verify(repository, never()).hasPriceInForce(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(repository, never()).spentThisMonth(any());
        verifyNoInteractions(auditRecorder, notifier);
    }

    @Test
    void underEightyPercent_runs_withoutAlert() {
        limit("50.00");
        spent("39.99");

        assertThat(guard.check(agent)).isEqualTo(Verdict.ALLOWED);

        verify(repository, never()).recordAlertOnce(any(), any(), any(), anyInt(), any(), any());
        verifyNoInteractions(notifier);
    }

    @Test
    void atEightyPercent_runs_andWarnsTheOwnerOnce() {
        limit("50.00");
        spent("40.00");

        assertThat(guard.check(agent)).isEqualTo(Verdict.ALLOWED);

        verify(repository).recordAlertOnce(any(), eq(clientId), eq(LocalDate.of(2026, 10, 1)), eq(80),
                eq(new BigDecimal("50.00")), eq(new BigDecimal("40.00")));
        ArgumentCaptor<SpendAlert> alert = ArgumentCaptor.forClass(SpendAlert.class);
        verify(notifier).thresholdReached(alert.capture());
        assertThat(alert.getValue().period()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(alert.getValue().reached()).isFalse();
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo(UsageAuditEvents.SPEND_THRESHOLD_REACHED);
        assertThat(audit.getValue().payload()).containsEntry("threshold_percent", 80)
                .containsEntry("period", "2026-10");
    }

    @Test
    void atTheLimit_isRefused_andTellsTheOwner() {
        limit("50.00");
        spent("50.01");

        assertThat(guard.check(agent)).isEqualTo(Verdict.LIMIT_REACHED);

        ArgumentCaptor<SpendAlert> alert = ArgumentCaptor.forClass(SpendAlert.class);
        verify(notifier).thresholdReached(alert.capture());
        assertThat(alert.getValue().reached()).isTrue();
    }

    @Test
    void aThresholdAlreadyReported_thisMonth_isNotReportedAgain() {
        limit("50.00");
        spent("60.00");
        when(repository.recordAlertOnce(any(), any(), any(), anyInt(), any(), any())).thenReturn(0);

        assertThat(guard.check(agent)).isEqualTo(Verdict.LIMIT_REACHED);

        verifyNoInteractions(notifier, auditRecorder);
    }

    @Test
    void withALimit_aModelWithoutPrice_isAMisconfiguration() {
        limit("50.00");
        when(repository.hasPriceInForce("anthropic", "claude-sonnet-5-5", false)).thenReturn(false);

        assertThat(guard.check(agent)).isEqualTo(Verdict.UNPRICED_MODEL);

        verify(repository, never()).spentThisMonth(any());
    }

    @Test
    void withALimit_aLongCacheAgent_needsTheLongWritePrice() {
        limit("50.00");
        spent("1.00");
        Agent longCache = agent.withPromptCacheRetention(PromptCacheRetention.LONG);
        when(repository.hasPriceInForce("anthropic", "claude-sonnet-5-5", true)).thenReturn(false, true);

        assertThat(guard.check(longCache)).isEqualTo(Verdict.UNPRICED_MODEL);
        assertThat(guard.check(longCache)).isEqualTo(Verdict.ALLOWED);
        assertThat(guard.check(agent)).isEqualTo(Verdict.ALLOWED); // a short-cache agent does not need it
    }

    @Test
    void transcription_withoutALimit_runs_andWithOne_needsAnAudioPrice() {
        assertThat(guard.checkTranscription(clientId, "whisper", "small")).isEqualTo(Verdict.ALLOWED);
        verify(repository, never()).hasAudioPriceInForce(any(), any());

        limit("50.00");
        when(repository.hasAudioPriceInForce("whisper", "small")).thenReturn(false, true);

        assertThat(guard.checkTranscription(clientId, "whisper", "small")).isEqualTo(Verdict.UNPRICED_MODEL);
        assertThat(guard.checkTranscription(clientId, "whisper", "small")).isEqualTo(Verdict.ALLOWED);
    }

    private void limit(String usd) {
        when(repository.findByTenantId(clientId)).thenReturn(Optional.of(mapper.toEntity(
                SpendLimit.create(clientId, new BigDecimal(usd), UUID.randomUUID(), NOW))));
    }

    private void spent(String usd) {
        when(repository.spentThisMonth(clientId)).thenReturn(List.<Object[]>of(new Object[] {new BigDecimal(usd), 0L}));
    }
}
