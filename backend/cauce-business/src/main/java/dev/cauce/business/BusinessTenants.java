package dev.cauce.business;

import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.core.tenant.Tier;
import dev.cauce.memory.tenant.TenantEntity;
import dev.cauce.memory.tenant.TenantRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The check every business record starts with (ADR 0007, B5): the tenant is visible under the
 * current context (RLS) and is a business — a CLIENT tenant, the tier agents belong to. A tenant
 * outside the caller's scope is a not-found, indistinguishable from one that does not exist.
 * Called from inside the callers' transactions.
 */
@Component
public class BusinessTenants {

    private final TenantRepository tenantRepository;

    public BusinessTenants(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * @throws TenantNotFoundException if the tenant is not visible
     * @throws InvalidTenantTierException if it is not a CLIENT tenant
     */
    public void requireBusiness(UUID tenantId) {
        TenantEntity tenant = tenantRepository.findById(tenantId).orElseThrow(() ->
                new TenantNotFoundException("No tenant found for id " + tenantId));
        if (tenant.getTier() != Tier.CLIENT) {
            throw new InvalidTenantTierException("Business records belong to a CLIENT tenant, but " + tenantId
                    + " is " + tenant.getTier());
        }
    }
}
