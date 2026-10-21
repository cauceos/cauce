package dev.cauce.core.business.profile;

import java.util.Optional;
import java.util.UUID;

/**
 * Port: where an agent's tools read the business profile from (ADR 0007, B2). The native adapter
 * is cauce-business; an external system holding the same facts would be another adapter.
 */
public interface BusinessProfileSource {

    /** The profile of the business {@code tenantId}, if one is loaded and visible. */
    Optional<BusinessProfile> findProfile(UUID tenantId);
}
