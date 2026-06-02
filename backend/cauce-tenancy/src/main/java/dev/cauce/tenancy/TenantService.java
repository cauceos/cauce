package dev.cauce.tenancy;

import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.core.tenant.Tier;
import dev.cauce.memory.tenant.TenantEntity;
import dev.cauce.memory.tenant.TenantMapper;
import dev.cauce.memory.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for creating tenants. Each tenant-scoped method runs in its own
 * transaction; {@code RlsContextAspect} establishes the RLS context from
 * {@code TenantContext} before it runs. Bootstrap is the exception: it has no tenant
 * context and is delegated to {@link OperatorBootstrap}, which writes through the
 * privileged owner connection that bypasses RLS.
 */
@Service
public class TenantService {

    private final TenantRepository repository;
    private final TenantMapper mapper;
    private final OperatorBootstrap operatorBootstrap;

    public TenantService(TenantRepository repository, TenantMapper mapper,
                         OperatorBootstrap operatorBootstrap) {
        this.repository = repository;
        this.mapper = mapper;
        this.operatorBootstrap = operatorBootstrap;
    }

    /**
     * Creates the first operator. Bootstrap-only: it runs without a tenant context, so it
     * is written via {@link OperatorBootstrap} on the owner connection (which bypasses RLS)
     * rather than the RLS-scoped {@code cauce_app} path. Not {@code @Transactional}: it does
     * not go through {@code RlsContextAspect}, and a single INSERT is atomic on its own.
     *
     * <p>TODO: enforce single-bootstrap (or authorize operator creation) once an
     * authentication layer exists; today this simply creates an operator.
     */
    public Tenant bootstrapOperator(String name) {
        return operatorBootstrap.insertOperator(Tenant.operator(name));
    }

    @Transactional
    public Tenant createPartner(String name, UUID operatorId) {
        requireTier(operatorId, Tier.OPERATOR, "an OPERATOR");
        return persist(Tenant.partner(name, operatorId));
    }

    @Transactional
    public Tenant createClient(String name, UUID partnerId) {
        requireTier(partnerId, Tier.PARTNER, "a PARTNER");
        return persist(Tenant.client(name, partnerId));
    }

    /**
     * Returns the tenant by id, or throws {@link TenantNotFoundException} if it does not
     * exist or is not visible to the current context. RLS does the visibility filtering;
     * not-found and not-visible are intentionally indistinguishable.
     */
    @Transactional
    public Tenant getTenant(UUID id) {
        return repository.findById(id).map(mapper::toDomain).orElseThrow(() ->
                new TenantNotFoundException("No tenant found for id " + id));
    }

    /**
     * Lists the direct children of the given tenant. RLS filters out anything the current
     * context cannot see, so a parent outside the caller's scope yields an empty list.
     */
    @Transactional
    public List<Tenant> listChildren(UUID parentId) {
        return repository.findByParentTenantId(parentId).stream().map(mapper::toDomain).toList();
    }

    private void requireTier(UUID parentId, Tier expected, String label) {
        TenantEntity parent = repository.findById(parentId).orElseThrow(() ->
                new TenantNotFoundException("No tenant found for id " + parentId));
        if (parent.getTier() != expected) {
            throw new InvalidTenantTierException(
                    "Parent " + parentId + " must be " + label + " but was " + parent.getTier());
        }
    }

    private Tenant persist(Tenant tenant) {
        return mapper.toDomain(repository.save(mapper.toEntity(tenant)));
    }
}
