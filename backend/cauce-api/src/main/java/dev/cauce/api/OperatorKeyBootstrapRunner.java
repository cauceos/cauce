package dev.cauce.api;

import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.ApiKeyCreationResult;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.OperatorBootstrap;
import dev.cauce.tenancy.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * On first startup (an empty database), creates the root operator and mints its first API key,
 * logging the plaintext exactly once. Key issuance is not exposed over REST, so this is the only
 * way to obtain the initial credential that bootstraps the whole tenant hierarchy. Once an
 * operator exists, every subsequent startup is a no-op.
 *
 * <p>The key is minted by reusing {@link ApiKeyService#createApiKey} under the operator's own
 * tenant context (set right after the operator row exists), so it follows the exact same
 * generation, hashing and persistence path as any other key — nothing is reimplemented and no
 * credential is committed to version control.
 *
 * <p>Excluded from the {@code test} profile: integration tests manage their own bootstrap and
 * mint their own keys, and must not race this runner.
 */
@Component
@Profile("!test")
public class OperatorKeyBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OperatorKeyBootstrapRunner.class);

    private final OperatorBootstrap operatorBootstrap;
    private final TenantService tenantService;
    private final ApiKeyService apiKeyService;

    public OperatorKeyBootstrapRunner(OperatorBootstrap operatorBootstrap,
                                      TenantService tenantService,
                                      ApiKeyService apiKeyService) {
        this.operatorBootstrap = operatorBootstrap;
        this.tenantService = tenantService;
        this.apiKeyService = apiKeyService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (operatorBootstrap.operatorExists()) {
            return;
        }
        Tenant operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        try {
            ApiKeyCreationResult bootstrapKey = apiKeyService.createApiKey(operator.id(), "bootstrap");
            log.warn("Bootstrapped operator {} ({}). Store its API key now — it is shown ONCE and "
                            + "cannot be recovered: {}",
                    operator.name(), operator.id(), bootstrapKey.plaintextKey());
        } finally {
            TenantContext.clear();
        }
    }
}
