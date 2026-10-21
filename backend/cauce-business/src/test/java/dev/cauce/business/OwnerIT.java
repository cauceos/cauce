package dev.cauce.business;

import static dev.cauce.business.support.BusinessFixture.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.business.handoff.HandoffService;
import dev.cauce.business.owner.OwnerNotification;
import dev.cauce.business.owner.OwnerNotificationDispatcher;
import dev.cauce.business.owner.OwnerNotificationService;
import dev.cauce.business.owner.OwnerRecipient;
import dev.cauce.business.owner.OwnerService;
import dev.cauce.business.owner.OwnerService.IssuedCode;
import dev.cauce.business.profile.BusinessProfileService;
import dev.cauce.business.support.AbstractBusinessIntegrationTest;
import dev.cauce.business.support.BusinessFixture;
import dev.cauce.core.agent.Agent;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.handoff.Handoff;
import dev.cauce.core.business.handoff.HandoffSink.HandoffReceipt;
import dev.cauce.core.business.handoff.InvalidHandoffCategoryException;
import dev.cauce.core.business.owner.OwnerNotifier;
import dev.cauce.core.business.owner.OwnerNotifier.Delivery;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.inbound.InboundMessageInterceptor.InboundText;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.TenantService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The owner of a business end to end below the channel layer (ADR 0007, B7, B8, B9): binding the
 * owner bot with a single-use code, handing a case over, and the outbox delivering — or recording
 * why it did not deliver — the owner's notice. The channel is a recording {@link OwnerNotifier}.
 */
@Import(OwnerIT.RecordingNotifierConfig.class)
class OwnerIT extends AbstractBusinessIntegrationTest {

    /** Records every send and answers with the armed outcome. */
    static class RecordingNotifier implements OwnerNotifier {
        final List<String> sent = new ArrayList<>();
        Function<Recipient, Delivery> outcome = recipient -> Delivery.sent();

        @Override
        public Delivery send(Recipient recipient, String text) {
            sent.add(text);
            return outcome.apply(recipient);
        }
    }

    @TestConfiguration
    static class RecordingNotifierConfig {
        @Bean
        RecordingNotifier recordingNotifier() {
            return new RecordingNotifier();
        }
    }

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private BusinessProfileService profileService;

    @Autowired
    private OwnerService ownerService;

    @Autowired
    private HandoffService handoffService;

    @Autowired
    private OwnerNotificationService notifications;

    @Autowired
    private OwnerNotificationDispatcher dispatcher;

    @Autowired
    private RecordingNotifier notifier;

    private BusinessFixture f;
    private UUID ownerBotId;
    private UUID otherBotId;

    @BeforeEach
    void setUp() {
        truncateAll();
        notifier.sent.clear();
        notifier.outcome = recipient -> Delivery.sent();
        f = BusinessFixture.seed(tenantService, agentService, conversationService);
        as(f.clientA(), () -> profileService.putProfile(f.clientA().id(), "Bar La Esquina", "Europe/Madrid", "es",
                List.of(), List.of("evento", "queja", "grupo")));
        Agent ownerAgent = as(f.clientA(), () -> agentService.createAgent(f.clientA().id(), "Dueño", "Fase 2.",
                "anthropic", "claude-sonnet-4-7"));
        ownerBotId = insertTelegramConfig(f.clientA().id(), ownerAgent.id());
        Agent otherAgent = as(f.clientB(), () -> agentService.createAgent(f.clientB().id(), "Otro", "p",
                "anthropic", "claude-sonnet-4-7"));
        otherBotId = insertTelegramConfig(f.clientB().id(), otherAgent.id());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aLinkCode_bindsTheOwnersChat_once_andOnlyOnItsOwnBot() {
        IssuedCode issued = as(f.partner(), () -> ownerService.issueLinkCode(f.clientA().id(), ownerBotId));

        // On another business's bot the code means nothing: the text goes on to that bot's agent.
        assertThat(intercept(f.clientB().id(), otherBotId, "999", "/start " + issued.code())).isEmpty();
        // Not a code: it goes on to the owner agent.
        assertThat(intercept(f.clientA().id(), ownerBotId, "777", "hola")).isEmpty();

        assertThat(intercept(f.clientA().id(), ownerBotId, "777", "/start " + issued.code().toLowerCase()))
                .contains("Listo: este chat recibirá los avisos de Bar La Esquina.");
        // A redelivery by the same chat is answered again and binds nothing new; another chat is refused.
        assertThat(intercept(f.clientA().id(), ownerBotId, "777", issued.code())).isPresent();
        assertThat(intercept(f.clientA().id(), ownerBotId, "888", issued.code()))
                .contains("Ese código de vinculación ha caducado o ya se usó. Pide uno nuevo.");

        OwnerRecipient owner = as(f.clientA(), () -> ownerService.findOwner(f.clientA().id())).orElseThrow();
        assertThat(owner.channelConfigId()).isEqualTo(ownerBotId);
        assertThat(new JdbcTemplate(adminDataSource).queryForObject(
                "SELECT count(*) FROM audit_outbox WHERE event_type = 'admin.owner.linked'", Long.class)).isEqualTo(1);
        assertThat(new JdbcTemplate(adminDataSource).queryForObject(
                "SELECT count(*) FROM owner_link_codes WHERE code_hash = ?", Long.class, issued.code())).isZero();
    }

    @Test
    void aLinkCode_isIssuedOnlyForAnActiveBotOfTheBusiness() {
        assertThatThrownBy(() -> as(f.partner(), () -> ownerService.issueLinkCode(f.clientA().id(), otherBotId)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(f.clientA(), () -> ownerService.issueLinkCode(f.clientA().id(), UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHandoff_keepsTheConversationOpen_queuesTheNotice_andTheDispatcherSendsIt() {
        linkOwner();

        HandoffReceipt receipt = as(f.clientA(), () -> handoffService.open(requester(), new Handoff.Request("Evento",
                "Cumpleaños de 25 personas el 14 de noviembre", "Marta", "+34 600 111 222")));

        assertThat(receipt.ownerNotified()).isTrue();
        assertThat(receipt.handoff().category()).isEqualTo("evento");
        assertThat(as(f.clientA(), () -> conversationService.getConversation(f.conversation().id())).orElseThrow()
                .status().name()).isEqualTo("OPEN");
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(notifier.sent).singleElement().asString().contains("evento").contains("Marta")
                .contains(receipt.handoff().code());
        assertThat(only().status()).isEqualTo(OwnerNotification.Status.SENT);
        assertThat(dispatcher.dispatchDue()).isZero();
        String audit = new JdbcTemplate(adminDataSource).queryForObject("SELECT payload::text FROM audit_outbox "
                + "WHERE event_type = 'conduct.human.escalation'", String.class);
        assertThat(audit).contains("\"category\": \"evento\"").doesNotContain("Marta").doesNotContain("Cumpleaños");
    }

    @Test
    void outsideTheReplyWindow_theNoticeIsNotSent_andTheCaseStaysOpenForTheOwner() {
        linkOwner();
        notifier.outcome = recipient -> Delivery.notSent(Delivery.OUTSIDE_REPLY_WINDOW);
        as(f.clientA(), () -> handoffService.open(requester(), new Handoff.Request("queja", "La sopa estaba fría",
                "Luis", null)));

        dispatcher.dispatchDue();

        OwnerNotification notice = only();
        assertThat(notice.status()).isEqualTo(OwnerNotification.Status.NOT_SENT);
        assertThat(notice.reason()).isEqualTo("outside_reply_window");
        assertThat(as(f.clientA(), () -> handoffService.openFor(f.clientA().id()))).hasSize(1);
    }

    @Test
    void aFailingSend_isRetried_thenFails_andWithoutAnOwnerNothingIsAttempted() {
        as(f.clientA(), () -> handoffService.open(requester(), new Handoff.Request("grupo", "Mesa para 12", "Ana", null)));
        assertThat(only().reason()).isEqualTo(OwnerNotification.NO_OWNER_RECIPIENT);
        assertThat(dispatcher.dispatchDue()).isZero();

        new JdbcTemplate(adminDataSource).update("DELETE FROM owner_notifications");
        linkOwner();
        notifier.outcome = recipient -> Delivery.failed("HTTP 403");
        as(f.clientA(), () -> handoffService.open(requester(), new Handoff.Request("grupo", "Mesa para 12", "Ana", null)));
        for (int attempt = 1; attempt <= 5; attempt++) {
            new JdbcTemplate(adminDataSource).update("UPDATE owner_notifications SET next_attempt_at = now()");
            dispatcher.dispatchDue();
        }
        assertThat(only().status()).isEqualTo(OwnerNotification.Status.FAILED);
        assertThat(only().attempts()).isEqualTo(5);
        assertThat(notifier.sent).hasSize(5);
    }

    @Test
    void aCategoryTheBusinessDoesNotList_isRefused() {
        assertThatThrownBy(() -> as(f.clientA(), () -> handoffService.open(requester(),
                new Handoff.Request("boda", "s", "Ana", null))))
                .isInstanceOf(InvalidHandoffCategoryException.class).hasMessageContaining("evento");
    }

    private void linkOwner() {
        IssuedCode issued = as(f.clientA(), () -> ownerService.issueLinkCode(f.clientA().id(), ownerBotId));
        assertThat(intercept(f.clientA().id(), ownerBotId, "777", issued.code())).isPresent();
    }

    private Optional<String> intercept(UUID tenantId, UUID configId, String chatId, String text) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return ownerService.intercept(new InboundText(tenantId, configId, "telegram",
                    IdentityKind.PROVIDER_USER_ID, chatId, text));
        } finally {
            TenantContext.clear();
        }
    }

    private OwnerNotification only() {
        List<OwnerNotification> all = as(f.clientA(), () -> notifications.listForTenant(f.clientA().id(), null, 10));
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private Booking.Requester requester() {
        return new Booking.Requester(f.clientA().id(), f.agent().id(), f.conversation().id(),
                f.conversation().identityId(), UUID.randomUUID());
    }

    private UUID insertTelegramConfig(UUID tenantId, UUID agentId) {
        UUID id = UUID.randomUUID();
        new JdbcTemplate(adminDataSource).update("INSERT INTO channel_configs (id, tenant_id, agent_id, channel_type, "
                + "credential, webhook_secret_hash, status) VALUES (?, ?, ?, 'telegram', 'token', 'hash', 'ACTIVE')",
                id, tenantId, agentId);
        return id;
    }
}
