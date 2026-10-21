package dev.cauce.business.owner;

import dev.cauce.core.business.owner.OwnerNotifier;
import dev.cauce.core.business.owner.OwnerNotifier.Delivery;
import dev.cauce.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the owner notifications (ADR 0007, B9). Each tick claims due notices across tenants
 * through the V37 {@code SECURITY DEFINER} function (ADR 0001), then, for each, sets that tenant's
 * context and reads, sends and records under RLS. The send itself runs outside any transaction.
 * Without an {@link OwnerNotifier} in the application (no channel layer), nothing is claimed.
 */
@Component
public class OwnerNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OwnerNotificationDispatcher.class);

    private final OwnerNotificationService notifications;
    private final ObjectProvider<OwnerNotifier> notifier;
    private final OwnerNotificationProperties properties;

    public OwnerNotificationDispatcher(OwnerNotificationService notifications, ObjectProvider<OwnerNotifier> notifier,
                                       OwnerNotificationProperties properties) {
        this.notifications = notifications;
        this.notifier = notifier;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${cauce.business.notifications.interval-ms:10000}")
    public void scheduledDispatch() {
        if (properties.isEnabled()) {
            dispatchDue();
        }
    }

    /** Claims and sends the due notices once; returns how many were claimed. */
    public int dispatchDue() {
        OwnerNotifier sender = notifier.getIfAvailable();
        if (sender == null) {
            return 0;
        }
        List<OwnerNotificationRepository.Claim> claims = notifications.claimDue(properties.getBatchSize(),
                properties.getLeaseSeconds());
        for (OwnerNotificationRepository.Claim claim : claims) {
            TenantContext.setCurrentTenantId(claim.getTenantId());
            try {
                Optional<OwnerNotificationService.Send> send = notifications.prepare(claim.getId());
                if (send.isPresent()) {
                    Delivery delivery = sendSafely(sender, send.get());
                    OwnerNotification recorded = notifications.record(claim.getId(), delivery,
                            properties.getMaxAttempts());
                    log.info("Owner notification {} of tenant {}: {} {}", recorded.id(), recorded.tenantId(),
                            recorded.status(), recorded.reasonIfAny().orElse(""));
                }
            } catch (RuntimeException e) {
                log.warn("Owner notification {} could not be dispatched; it is retried after its lease: {}",
                        claim.getId(), e.toString(), e);
            } finally {
                TenantContext.clear();
            }
        }
        return claims.size();
    }

    private static Delivery sendSafely(OwnerNotifier sender, OwnerNotificationService.Send send) {
        try {
            return sender.send(send.recipient(), send.notification().content());
        } catch (RuntimeException e) {
            return Delivery.failed(e.getClass().getSimpleName());
        }
    }
}
