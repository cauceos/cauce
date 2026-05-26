package dev.cauce.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.PendingInvocationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PendingInvocationReaperTest {

    private PendingInvocationService pendingInvocationService;
    private PendingInvocationWorkerProperties properties;
    private PendingInvocationReaper reaper;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID triggerMessageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        pendingInvocationService = Mockito.mock(PendingInvocationService.class);
        properties = new PendingInvocationWorkerProperties();
        properties.setRetryBaseIntervalSeconds(30L);
        properties.getReaper().setTimeoutMs(60_000L);
        reaper = new PendingInvocationReaper(pendingInvocationService, properties);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void reapOrphanedInvocations_whenNoneFound_doesNothing() {
        when(pendingInvocationService.findOrphanedSince(any())).thenReturn(List.of());

        reaper.reapOrphanedInvocations();

        verify(pendingInvocationService, never())
                .releaseForRetry(any(), anyString(), anyLong());
        verify(pendingInvocationService, never()).markAbandoned(any(), anyString());
    }

    @Test
    void reapOrphanedInvocations_withBudgetRemaining_releasesForRetry() {
        PendingInvocation orphan = claimedAtAttempt(1);
        when(pendingInvocationService.findOrphanedSince(any())).thenReturn(List.of(orphan));

        reaper.reapOrphanedInvocations();

        verify(pendingInvocationService)
                .releaseForRetry(eq(orphan.id()), anyString(), eq(30L));
        verify(pendingInvocationService, never()).markAbandoned(any(), anyString());
    }

    @Test
    void reapOrphanedInvocations_whenBudgetExhausted_marksAbandoned() {
        PendingInvocation orphan = claimedAtAttempt(3);
        when(pendingInvocationService.findOrphanedSince(any())).thenReturn(List.of(orphan));

        reaper.reapOrphanedInvocations();

        verify(pendingInvocationService).markAbandoned(eq(orphan.id()), anyString());
        verify(pendingInvocationService, never())
                .releaseForRetry(any(), anyString(), anyLong());
    }

    @Test
    void reapOrphanedInvocations_setsTenantContextPerRowAndClearsIt() {
        PendingInvocation orphan = claimedAtAttempt(1);
        when(pendingInvocationService.findOrphanedSince(any())).thenReturn(List.of(orphan));
        AtomicReference<UUID> captured = new AtomicReference<>();
        Mockito.doAnswer(call -> {
            captured.set(TenantContext.getCurrentTenantId().orElse(null));
            return null;
        }).when(pendingInvocationService).releaseForRetry(any(), anyString(), anyLong());

        reaper.reapOrphanedInvocations();

        assertThat(captured.get()).isEqualTo(tenantId);
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    @Test
    void reapOrphanedInvocations_callsFindOrphanedSinceWithThresholdRelativeToNow() {
        when(pendingInvocationService.findOrphanedSince(any())).thenReturn(List.of());
        Instant before = Instant.now();

        reaper.reapOrphanedInvocations();

        Instant expectedUpper = Instant.now().minusMillis(properties.getReaper().getTimeoutMs());
        Instant expectedLower = before.minusMillis(properties.getReaper().getTimeoutMs());
        org.mockito.ArgumentCaptor<Instant> captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(pendingInvocationService).findOrphanedSince(captor.capture());
        assertThat(captor.getValue())
                .isAfterOrEqualTo(expectedLower)
                .isBeforeOrEqualTo(expectedUpper.plusSeconds(1));
    }

    @Test
    void reapOrphanedInvocations_whenScanFails_doesNotPropagate() {
        when(pendingInvocationService.findOrphanedSince(any()))
                .thenThrow(new RuntimeException("DB outage"));

        // Must not throw — the scheduler thread keeps running.
        reaper.reapOrphanedInvocations();
    }

    @Test
    void reapOrphanedInvocations_whenOneRowFails_continuesWithRemaining() {
        PendingInvocation a = claimedAtAttempt(1);
        PendingInvocation b = claimedAtAttempt(1);
        when(pendingInvocationService.findOrphanedSince(any())).thenReturn(List.of(a, b));
        Mockito.doThrow(new RuntimeException("locked"))
                .when(pendingInvocationService).releaseForRetry(eq(a.id()), anyString(), anyLong());

        reaper.reapOrphanedInvocations();

        verify(pendingInvocationService).releaseForRetry(eq(b.id()), anyString(), eq(30L));
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    private PendingInvocation claimedAtAttempt(int targetAttempt) {
        PendingInvocation invocation =
                PendingInvocation.create(tenantId, conversationId, triggerMessageId).claim("w");
        while (invocation.attemptCount() < targetAttempt) {
            invocation = invocation.releaseForRetry("transient", 1L).claim("w");
        }
        return invocation;
    }
}
