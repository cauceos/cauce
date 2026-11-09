package dev.cauce.core.usage;

import java.util.UUID;

/**
 * Port: whether something a tenant runs makes a spend limit mandatory for it (ADR 0014, WC3). A
 * channel open to anonymous traffic does: while it is active, the tenant's limit cannot be
 * removed. Implemented by the module that owns such a channel; the spend limit's service asks
 * every implementation before removing a limit, without knowing what they are.
 */
public interface SpendLimitRequirement {

    /** Whether {@code tenantId} has something active that needs a spend limit. */
    boolean requiresSpendLimit(UUID tenantId);
}
