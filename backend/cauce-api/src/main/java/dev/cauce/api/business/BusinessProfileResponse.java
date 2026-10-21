package dev.cauce.api.business;

import dev.cauce.core.business.profile.BusinessProfile;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A business profile over the wire; {@code facts} in the order the business wrote them. */
public record BusinessProfileResponse(
        UUID id,
        UUID tenantId,
        String name,
        String timeZone,
        String locale,
        Map<String, String> facts,
        Instant createdAt,
        Instant updatedAt) {

    public static BusinessProfileResponse from(BusinessProfile profile) {
        Map<String, String> facts = new LinkedHashMap<>();
        profile.facts().forEach(fact -> facts.put(fact.key(), fact.value()));
        return new BusinessProfileResponse(profile.id(), profile.tenantId(), profile.name(),
                profile.timeZone().getId(), profile.locale(), facts, profile.createdAt(), profile.updatedAt());
    }
}
