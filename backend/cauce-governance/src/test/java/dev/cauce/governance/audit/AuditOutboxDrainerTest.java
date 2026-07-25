package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.core.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AuditOutboxDrainerTest {

    private final AuditOutboxDrainService drainService =
            Mockito.mock(AuditOutboxDrainService.class);
    private final AuditDrainerProperties properties = new AuditDrainerProperties();
    private final AuditOutboxDrainer drainer = new AuditOutboxDrainer(drainService, properties);

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void drainAll_drainsEachPendingTenantUnderItsOwnContext() {
        when(drainService.pendingTenants()).thenReturn(List.of(tenantA, tenantB));
        List<Optional<UUID>> contextsSeen = new ArrayList<>();
        when(drainService.drainBatch(Mockito.any(), Mockito.anyInt())).thenAnswer(call -> {
            contextsSeen.add(TenantContext.getCurrentTenantId());
            return 1;
        });

        drainer.drainAll();

        verify(drainService).drainBatch(tenantA, properties.getBatchSize());
        verify(drainService).drainBatch(tenantB, properties.getBatchSize());
        assertThat(contextsSeen)
                .containsExactly(Optional.of(tenantA), Optional.of(tenantB));
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    @Test
    void drainAll_whenOneTenantFails_stillDrainsTheOthersAndClearsContext() {
        when(drainService.pendingTenants()).thenReturn(List.of(tenantA, tenantB));
        when(drainService.drainBatch(tenantA, properties.getBatchSize()))
                .thenThrow(new IllegalStateException("boom"));
        when(drainService.drainBatch(tenantB, properties.getBatchSize())).thenReturn(1);

        drainer.drainAll();

        verify(drainService).drainBatch(tenantB, properties.getBatchSize());
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    @Test
    void drainAll_withNoPendingTenants_doesNothing() {
        when(drainService.pendingTenants()).thenReturn(List.of());

        drainer.drainAll();

        verify(drainService, Mockito.never()).drainBatch(Mockito.any(), Mockito.anyInt());
    }
}
