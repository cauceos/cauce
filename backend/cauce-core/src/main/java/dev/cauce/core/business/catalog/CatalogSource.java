package dev.cauce.core.business.catalog;

import java.util.Optional;
import java.util.UUID;

/**
 * Port: where an agent's tools read the business catalog from (ADR 0007, B2). The native adapter
 * is cauce-business; a point-of-sale or ERP catalog would be another adapter.
 */
public interface CatalogSource {

    /** The catalog of the business {@code tenantId}, if one is loaded and visible. */
    Optional<Catalog> findCatalog(UUID tenantId);
}
