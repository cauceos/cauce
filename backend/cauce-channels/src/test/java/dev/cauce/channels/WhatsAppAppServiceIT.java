package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.channels.support.AbstractChannelsIntegrationTest;
import dev.cauce.channels.whatsapp.app.WhatsAppApp;
import dev.cauce.channels.whatsapp.app.WhatsAppAppRegistration;
import dev.cauce.channels.whatsapp.app.WhatsAppAppService;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.tenancy.TenantService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WhatsApp apps (ADR 0006, W2) against a real PostgreSQL: hierarchical RLS of the app rows, the
 * tenant-less resolution the webhook depends on, and the secret sealed at rest.
 */
class WhatsAppAppServiceIT extends AbstractChannelsIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private WhatsAppAppService whatsAppAppService;

    @Autowired
    private ApiKeyHasher hasher;

    private JdbcTemplate jdbc;
    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(adminDataSource);
        truncateAll();
        TenantContext.clear();
        operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        clientA = tenantService.createClient("Client A", partner.id());
        clientB = tenantService.createClient("Client B", partner.id());
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void registerApp_byThePartner_storesTheSecretSealed_andTheVerifyTokenOnlyHashed() {
        WhatsAppAppRegistration registration = registerAs(partner.id(), partner.id());

        String storedSecret = jdbc.queryForObject("SELECT app_secret FROM whatsapp_apps WHERE id = ?",
                String.class, registration.app().id());
        String storedHash = jdbc.queryForObject("SELECT verify_token_hash FROM whatsapp_apps WHERE id = ?",
                String.class, registration.app().id());
        assertThat(storedSecret).startsWith("v1:").doesNotContain("meta-app-secret");
        assertThat(storedHash).isNotEqualTo(registration.verifyToken());
        assertThat(hasher.matches(registration.verifyToken(), storedHash)).isTrue();
        assertThat(registration.app().appSecret()).isEqualTo("meta-app-secret");
    }

    @Test
    void apps_areVisibleDownTheHierarchyOnly() {
        registerAs(partner.id(), partner.id());

        assertThat(countAs("whatsapp_apps", partner.id())).isEqualTo(1);
        assertThat(countAs("whatsapp_apps", operator.id())).isEqualTo(1);
        assertThat(countAs("whatsapp_apps", clientA.id())).isZero(); // a client does not see its partner's app
        assertThat(countAs("whatsapp_apps", null)).isZero();
    }

    @Test
    void registerApp_forATenantOutsideTheCallersScope_isNotFound() {
        TenantContext.setCurrentTenantId(clientB.id());
        try {
            assertThatThrownBy(() -> whatsAppAppService.registerApp(clientA.id(), "1", "s"))
                    .isInstanceOf(TenantNotFoundException.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void resolveActiveApp_withoutTenantContext_opensTheSecret_andIgnoresDisabledApps() {
        WhatsAppApp app = registerAs(partner.id(), partner.id()).app();

        assertThat(whatsAppAppService.resolveActiveApp(app.id()))
                .map(WhatsAppApp::appSecret).contains("meta-app-secret");
        jdbc.update("UPDATE whatsapp_apps SET status = 'DISABLED' WHERE id = ?", app.id());
        assertThat(whatsAppAppService.resolveActiveApp(app.id())).isEmpty();
        assertThat(whatsAppAppService.resolveActiveApp(UUID.randomUUID())).isEmpty();
    }

    private WhatsAppAppRegistration registerAs(UUID context, UUID tenantId) {
        TenantContext.setCurrentTenantId(context);
        try {
            return whatsAppAppService.registerApp(tenantId, "1234567890", "meta-app-secret");
        } finally {
            TenantContext.clear();
        }
    }
}
