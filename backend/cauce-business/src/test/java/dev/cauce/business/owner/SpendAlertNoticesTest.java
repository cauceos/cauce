package dev.cauce.business.owner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.business.profile.BusinessProfileMapper;
import dev.cauce.business.profile.BusinessProfileRepository;
import dev.cauce.core.usage.SpendAlertNotifier.SpendAlert;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SpendAlertNoticesTest {

    private final OwnerNotificationService notifications = Mockito.mock(OwnerNotificationService.class);
    private final BusinessProfileRepository profiles = Mockito.mock(BusinessProfileRepository.class);
    private final SpendAlertNotices notices = new SpendAlertNotices(notifications, profiles,
            Mockito.mock(BusinessProfileMapper.class));
    private final UUID tenantId = UUID.randomUUID();

    @Test
    void thresholdReached_at80_queuesAWarning_aboutTheAlert() {
        when(profiles.findByTenantId(any())).thenReturn(Optional.empty());
        SpendAlert alert = alert(80);

        notices.thresholdReached(alert);

        verify(notifications).queue(eq(tenantId), eq(OwnerNotification.Kind.SPEND_WARNING), eq(alert.alertId()),
                startsWith("Aviso de gasto"));
    }

    @Test
    void thresholdReached_at100_queuesTheLimitReached() {
        when(profiles.findByTenantId(any())).thenReturn(Optional.empty());
        SpendAlert alert = alert(100);

        notices.thresholdReached(alert);

        verify(notifications).queue(eq(tenantId), eq(OwnerNotification.Kind.SPEND_LIMIT_REACHED),
                eq(alert.alertId()), startsWith("Límite de gasto alcanzado"));
    }

    private SpendAlert alert(int threshold) {
        return new SpendAlert(UUID.randomUUID(), tenantId, YearMonth.of(2026, 10), threshold, new BigDecimal("50.00"),
                new BigDecimal("45.00"));
    }
}
