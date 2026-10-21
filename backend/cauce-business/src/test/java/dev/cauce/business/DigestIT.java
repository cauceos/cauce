package dev.cauce.business;

import static dev.cauce.business.support.BusinessFixture.as;
import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.business.booking.AgendaService;
import dev.cauce.business.booking.BookingService;
import dev.cauce.business.digest.DailyDigest;
import dev.cauce.business.digest.DailyDigestScheduler;
import dev.cauce.business.digest.DailyDigestService;
import dev.cauce.business.gap.KnowledgeGapService;
import dev.cauce.business.handoff.HandoffService;
import dev.cauce.business.owner.OwnerNotification;
import dev.cauce.business.owner.OwnerNotificationService;
import dev.cauce.business.profile.BusinessProfileService;
import dev.cauce.business.support.AbstractBusinessIntegrationTest;
import dev.cauce.business.support.BusinessFixture;
import dev.cauce.core.business.booking.Agenda.Settings;
import dev.cauce.core.business.booking.Agenda.Shift;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.handoff.Handoff;
import dev.cauce.core.business.profile.BusinessProfile.DigestSchedule;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.TenantService;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The nightly summary against a real PostgreSQL (ADR 0007, B10): composed from the records, once
 * per business and local day whatever re-runs it, queued through the owner outbox, skipped when
 * empty if the business says so, and due only for the businesses that want one.
 */
class DigestIT extends AbstractBusinessIntegrationTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private BusinessProfileService profileService;

    @Autowired
    private AgendaService agendaService;

    @Autowired
    private BookingService bookings;

    @Autowired
    private HandoffService handoffs;

    @Autowired
    private KnowledgeGapService questions;

    @Autowired
    private OwnerNotificationService notifications;

    @Autowired
    private DailyDigestService digests;

    @Autowired
    private DailyDigestScheduler scheduler;

    private BusinessFixture f;

    @BeforeEach
    void setUp() {
        truncateAll();
        f = BusinessFixture.seed(tenantService, agentService, conversationService);
        as(f.clientA(), () -> agendaService.putAgenda(f.clientA().id(), new Settings("comensales", 8, 10, 60, false,
                List.of(new Shift("cena", EnumSet.allOf(DayOfWeek.class), LocalTime.of(20, 30), LocalTime.of(23, 0),
                        50)), Set.of())));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theSummary_reportsTomorrowTheOpenCasesAndTheQuestions_andIsSentOncePerDay() {
        profile(f.clientA().id(), new DigestSchedule(LocalTime.MIDNIGHT, false));
        Booking booking = confirmTomorrow("Ana López");
        Handoff handoff = as(f.clientA(), () -> handoffs.open(requester(), new Handoff.Request("evento",
                "Cumpleaños de 25 personas", "Marta", null))).handoff();
        as(f.clientA(), () -> questions.recordUnansweredQuestion(f.clientA().id(), f.agent().id(),
                f.conversation().id(), "¿Tenéis wifi?"));

        DailyDigest first = as(f.clientA(), () -> digests.runToday(f.clientA().id()));
        DailyDigest again = as(f.clientA(), () -> digests.runToday(f.clientA().id()));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(first.localDate()).isEqualTo(LocalDate.now(MADRID));
        assertThat(first.content()).contains("Ana López").contains(booking.code()).contains(handoff.code())
                .contains("¿Tenéis wifi?");
        assertThat(first.bookingCount()).isEqualTo(1);
        assertThat(first.notified()).isTrue();
        List<OwnerNotification> queued = as(f.clientA(), () -> notifications.listForTenant(f.clientA().id(), null, 10))
                .stream().filter(n -> n.kind() == OwnerNotification.Kind.DAILY_DIGEST).toList();
        assertThat(queued).singleElement().satisfies(n -> {
            assertThat(n.subjectId()).isEqualTo(first.id());
            assertThat(n.content()).isEqualTo(first.content());
            // No owner linked in this test: recorded, never attempted.
            assertThat(n.reason()).isEqualTo(OwnerNotification.NO_OWNER_RECIPIENT);
        });
        assertThat(countAs("daily_digests", f.clientB().id())).isZero();
    }

    @Test
    void theQuestions_areThoseSinceThePreviousSummary() {
        profile(f.clientA().id(), new DigestSchedule(LocalTime.MIDNIGHT, false));
        as(f.clientA(), () -> questions.recordUnansweredQuestion(f.clientA().id(), f.agent().id(),
                f.conversation().id(), "¿Pregunta de ayer?"));
        Instant yesterdaysCut = Instant.now().truncatedTo(ChronoUnit.MICROS);
        new JdbcTemplate(adminDataSource).update("INSERT INTO daily_digests (id, tenant_id, local_date, covered_from, "
                + "covered_to, booking_count, handoff_count, question_count, content, notified) VALUES (?, ?, ?, ?, ?, "
                + "0, 0, 1, 'ayer', true)", UUID.randomUUID(), f.clientA().id(), LocalDate.now(MADRID).minusDays(1),
                java.sql.Timestamp.from(yesterdaysCut.minusSeconds(86_400)), java.sql.Timestamp.from(yesterdaysCut));
        as(f.clientA(), () -> questions.recordUnansweredQuestion(f.clientA().id(), f.agent().id(),
                f.conversation().id(), "¿Pregunta de hoy?"));

        DailyDigest today = as(f.clientA(), () -> digests.runToday(f.clientA().id()));

        assertThat(today.content()).contains("¿Pregunta de hoy?").doesNotContain("¿Pregunta de ayer?");
        assertThat(today.coveredFrom()).isEqualTo(yesterdaysCut);
    }

    @Test
    void anEmptyDay_ofABusinessThatSkipsThem_isRecordedButNotSent() {
        profile(f.clientA().id(), new DigestSchedule(LocalTime.MIDNIGHT, true));

        DailyDigest empty = as(f.clientA(), () -> digests.runToday(f.clientA().id()));

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.notified()).isFalse();
        assertThat(empty.content()).endsWith("Nada pendiente.");
        assertThat(as(f.clientA(), () -> notifications.listForTenant(f.clientA().id(), null, 10))).isEmpty();
    }

    @Test
    void theScheduler_runsOnlyTheBusinessesThatWantASummary_andOnlyOnce() {
        profile(f.clientA().id(), new DigestSchedule(LocalTime.MIDNIGHT, false));
        profile(f.clientB().id(), null);

        assertThat(scheduler.runDue()).isEqualTo(1);
        assertThat(scheduler.runDue()).isEqualTo(1); // due again, but today's exists: nothing new

        assertThat(new JdbcTemplate(adminDataSource).queryForObject("SELECT count(*) FROM daily_digests",
                Long.class)).isEqualTo(1);
        assertThat(new JdbcTemplate(adminDataSource).queryForObject(
                "SELECT count(*) FROM owner_notifications WHERE kind = 'DAILY_DIGEST'", Long.class)).isEqualTo(1);
    }

    private void profile(UUID tenantId, DigestSchedule digest) {
        as(tenantId.equals(f.clientA().id()) ? f.clientA() : f.clientB(), () -> profileService.putProfile(tenantId,
                "Bar La Esquina", "Europe/Madrid", "es", List.of(), List.of(), digest));
    }

    private Booking confirmTomorrow(String name) {
        LocalDate tomorrow = LocalDate.now(MADRID).plusDays(1);
        Booking held = as(f.clientA(), () -> bookings.propose(requester(),
                new Booking.Details(tomorrow, "cena", 4, name, null, null)));
        return as(f.clientA(), () -> bookings.confirm(requester(), held.code()));
    }

    private Booking.Requester requester() {
        return new Booking.Requester(f.clientA().id(), f.agent().id(), f.conversation().id(),
                f.conversation().identityId(), UUID.randomUUID());
    }
}
