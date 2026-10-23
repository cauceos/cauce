package dev.cauce.business.owner;

import dev.cauce.business.profile.BusinessProfileMapper;
import dev.cauce.business.profile.BusinessProfileRepository;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.usage.SpendAlertNotifier;
import org.springframework.stereotype.Component;

/**
 * The native adapter of {@link SpendAlertNotifier} (ADR 0010, S6): a spend alert becomes an owner
 * notice, in the business's language, queued in the transaction that recorded the alert. With no
 * owner linked, the notice is recorded NOT_SENT with its reason, like every owner notice.
 */
@Component
public class SpendAlertNotices implements SpendAlertNotifier {

    private final OwnerNotificationService notifications;
    private final BusinessProfileRepository profileRepository;
    private final BusinessProfileMapper profileMapper;

    public SpendAlertNotices(OwnerNotificationService notifications, BusinessProfileRepository profileRepository,
                             BusinessProfileMapper profileMapper) {
        this.notifications = notifications;
        this.profileRepository = profileRepository;
        this.profileMapper = profileMapper;
    }

    @Override
    public void thresholdReached(SpendAlert alert) {
        String locale = profileRepository.findByTenantId(alert.tenantId()).map(profileMapper::toDomain)
                .map(BusinessProfile::locale).orElse(null);
        notifications.queue(alert.tenantId(),
                alert.reached() ? OwnerNotification.Kind.SPEND_LIMIT_REACHED : OwnerNotification.Kind.SPEND_WARNING,
                alert.alertId(),
                alert.reached() ? OwnerMessages.spendLimitReached(locale, alert) : OwnerMessages.spendWarning(locale, alert));
    }
}
