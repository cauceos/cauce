package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.support.AbstractChannelsIntegrationTest;
import dev.cauce.channels.whatsapp.AppOutsideHierarchyException;
import dev.cauce.channels.whatsapp.PhoneNumberAlreadyBoundException;
import dev.cauce.channels.whatsapp.WhatsAppNumberService;
import dev.cauce.channels.whatsapp.app.WhatsAppApp;
import dev.cauce.channels.whatsapp.app.WhatsAppAppNotFoundException;
import dev.cauce.channels.whatsapp.app.WhatsAppAppService;
import dev.cauce.core.agent.Agent;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.TenantService;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Binding WhatsApp numbers to agents and routing events to them (ADR 0006, W2), against a real
 * PostgreSQL: the app-within-hierarchy rule, instance-wide uniqueness of a number, the tenant-less
 * routing the webhook depends on, and the access token sealed at rest.
 */
class WhatsAppNumberServiceIT extends AbstractChannelsIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private WhatsAppAppService appService;

    @Autowired
    private WhatsAppNumberService numberService;

    private JdbcTemplate jdbc;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;
    private Agent agentA;
    private Agent agentB;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(adminDataSource);
        truncateAll();
        TenantContext.clear();
        Tenant operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        clientA = tenantService.createClient("Client A", partner.id());
        clientB = tenantService.createClient("Client B", partner.id());
        TenantContext.setCurrentTenantId(clientA.id());
        agentA = agentService.createAgent(clientA.id(), "A", "p", "anthropic", "claude-sonnet-4-7");
        TenantContext.setCurrentTenantId(clientB.id());
        agentB = agentService.createAgent(clientB.id(), "B", "p", "anthropic", "claude-sonnet-4-7");
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void thePartnersApp_servesANumberOfItsClient_andTheTokenIsSealedAtRest() {
        WhatsAppApp app = registerApp(partner);

        ChannelConfig number = as(partner, () ->
                numberService.bindNumber(agentA.id(), app.id(), "1111111111", "EAAG-token-a")).config();

        assertThat(number.tenantId()).isEqualTo(clientA.id());
        assertThat(number.whatsappAppId()).isEqualTo(app.id());
        assertThat(jdbc.queryForObject("SELECT credential FROM channel_configs WHERE id = ?", String.class,
                number.id())).startsWith("v1:").doesNotContain("EAAG-token-a");
    }

    @Test
    void aClientsOwnApp_servesItsOwnNumbers() {
        WhatsAppApp app = registerApp(clientA);

        ChannelConfig number = as(clientA, () ->
                numberService.bindNumber(agentA.id(), app.id(), "1111111111", "t")).config();

        assertThat(number.tenantId()).isEqualTo(clientA.id());
    }

    @Test
    void aSiblingsApp_cannotServeANumber() {
        WhatsAppApp siblingApp = registerApp(clientB);

        // The partner sees both clients, so the app is visible; the hierarchy rule rejects it.
        assertThatThrownBy(() -> as(partner, () ->
                numberService.bindNumber(agentA.id(), siblingApp.id(), "1111111111", "t")))
                .isInstanceOf(AppOutsideHierarchyException.class);
    }

    @Test
    void aClient_cannotSeeItsPartnersApp_soCannotBindToIt() {
        WhatsAppApp app = registerApp(partner);

        assertThatThrownBy(() -> as(clientA, () ->
                numberService.bindNumber(agentA.id(), app.id(), "1111111111", "t")))
                .isInstanceOf(WhatsAppAppNotFoundException.class);
    }

    @Test
    void aNumber_isOneDoorAcrossTheInstance() {
        WhatsAppApp app = registerApp(partner);
        as(partner, () -> numberService.bindNumber(agentA.id(), app.id(), "1111111111", "t"));

        assertThatThrownBy(() -> as(partner, () ->
                numberService.bindNumber(agentB.id(), app.id(), "1111111111", "t")))
                .isInstanceOf(PhoneNumberAlreadyBoundException.class);
    }

    @Test
    void resolveNumber_routesWithoutTenantContext_onlyWithinItsOwnApp() {
        WhatsAppApp app = registerApp(partner);
        WhatsAppApp otherApp = registerApp(partner);
        ChannelConfig number = as(partner, () ->
                numberService.bindNumber(agentA.id(), app.id(), "1111111111", "EAAG-token-a")).config();

        assertThat(numberService.resolveNumber(app.id(), "1111111111")).hasValueSatisfying(resolved -> {
            assertThat(resolved.id()).isEqualTo(number.id());
            assertThat(resolved.tenantId()).isEqualTo(clientA.id());
            assertThat(resolved.credential()).isEqualTo("EAAG-token-a");
        });
        assertThat(numberService.resolveNumber(otherApp.id(), "1111111111")).isEmpty();
        assertThat(numberService.resolveNumber(app.id(), "2222222222")).isEmpty();
        jdbc.update("UPDATE channel_configs SET status = 'DISABLED' WHERE id = ?", number.id());
        assertThat(numberService.resolveNumber(app.id(), "1111111111")).isEmpty();
    }

    private WhatsAppApp registerApp(Tenant owner) {
        return as(owner, () -> appService.registerApp(owner.id(), "1234567890", "app-secret").app());
    }

    private <T> T as(Tenant tenant, Supplier<T> action) {
        TenantContext.setCurrentTenantId(tenant.id());
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }
}
