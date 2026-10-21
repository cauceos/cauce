package dev.cauce.business.profile;

import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfile.Fact;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Hand-written mapper between {@link BusinessProfile} and {@link BusinessProfileEntity}. */
@Component
public class BusinessProfileMapper {

    public BusinessProfileEntity toEntity(BusinessProfile profile) {
        List<Map<String, String>> facts = profile.facts().stream()
                .map(fact -> Map.of("key", fact.key(), "value", fact.value()))
                .toList();
        return new BusinessProfileEntity(profile.id(), profile.tenantId(), profile.name(),
                profile.timeZone().getId(), profile.locale(), facts, profile.handoffCategories().toArray(String[]::new),
                profile.digestSchedule().map(BusinessProfile.DigestSchedule::localTime).orElse(null),
                profile.digestSchedule().map(BusinessProfile.DigestSchedule::skipWhenEmpty).orElse(false),
                profile.createdAt(), profile.updatedAt());
    }

    public BusinessProfile toDomain(BusinessProfileEntity entity) {
        List<Fact> facts = entity.getFacts().stream()
                .map(fact -> new Fact(fact.get("key"), fact.get("value")))
                .toList();
        return BusinessProfile.rehydrate(entity.getId(), entity.getTenantId(), entity.getName(),
                entity.getTimeZone(), entity.getLocale(), facts, List.of(entity.getHandoffCategories()),
                entity.getDailyDigestTime() == null ? null
                        : new BusinessProfile.DigestSchedule(entity.getDailyDigestTime(), entity.isDailyDigestSkipEmpty()),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
