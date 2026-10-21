package dev.cauce.business;

import static dev.cauce.business.support.BusinessFixture.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.business.catalog.CatalogService;
import dev.cauce.business.gap.KnowledgeGapService;
import dev.cauce.business.profile.BusinessProfileService;
import dev.cauce.business.support.AbstractBusinessIntegrationTest;
import dev.cauce.business.support.BusinessFixture;
import dev.cauce.core.business.catalog.Catalog;
import dev.cauce.core.business.catalog.CatalogItem;
import dev.cauce.core.business.gap.KnowledgeGap;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfile.Fact;
import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.TenantService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The profile, the catalog and the unanswered questions of a business (ADR 0007, B4, B5) against
 * a real PostgreSQL, through the runtime {@code cauce_app}/RLS path: one per business, owned by
 * the business, visible up the hierarchy and never across it, audited by hash only.
 */
class BusinessKnowledgeIT extends AbstractBusinessIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private BusinessProfileService profileService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private KnowledgeGapService gapService;

    private BusinessFixture f;

    @BeforeEach
    void setUp() {
        truncateAll();
        f = BusinessFixture.seed(tenantService, agentService, conversationService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void putProfile_byThePartner_isOwnedByTheBusiness_andVisibleUpTheHierarchyOnly() {
        BusinessProfile profile = as(f.partner(), () -> profileService.putProfile(f.clientA().id(), "Bar La Esquina",
                "Europe/Madrid", null, List.of(new Fact("horario", "mar-dom 12:00-16:00"))));

        assertThat(profile.tenantId()).isEqualTo(f.clientA().id());
        assertThat(profile.id().version()).isEqualTo(7);
        assertThat(as(f.clientA(), () -> profileService.findProfile(f.clientA().id()))).contains(profile);
        assertThat(countAs("business_profiles", f.operator().id())).isEqualTo(1);
        assertThat(countAs("business_profiles", f.clientB().id())).isZero();
        assertThat(as(f.clientB(), () -> profileService.findProfile(f.clientA().id()))).isEmpty();
    }

    @Test
    void putProfile_twice_replacesTheContentOfTheSameProfile_andAuditsEachByHashOnly() {
        BusinessProfile first = as(f.clientA(), () -> profileService.putProfile(f.clientA().id(), "Bar", "UTC", "es",
                List.of(new Fact("terraza", "sí"))));
        BusinessProfile second = as(f.clientA(), () -> profileService.putProfile(f.clientA().id(), "Bar 2",
                "Europe/Madrid", "es", List.of(new Fact("terraza", "no"), new Fact("perros", "en terraza"))));

        assertThat(second.id()).isEqualTo(first.id());
        BusinessProfile read = as(f.clientA(), () -> profileService.findProfile(f.clientA().id())).orElseThrow();
        assertThat(read.name()).isEqualTo("Bar 2");
        assertThat(read.facts()).containsExactly(new Fact("terraza", "no"), new Fact("perros", "en terraza"));

        JdbcTemplate jdbc = new JdbcTemplate(adminDataSource);
        List<String> payloads = jdbc.queryForList("SELECT payload::text FROM audit_outbox WHERE event_type = "
                + "'admin.business_profile.updated' AND tenant_id = ?", String.class, f.clientA().id());
        assertThat(payloads).hasSize(2).allSatisfy(payload ->
                assertThat(payload).contains("content_hash").doesNotContain("terraza").doesNotContain("Bar"));
    }

    @Test
    void putProfile_isRefusedForANonBusiness_outsideTheScope_andWithoutAContext() {
        assertThatThrownBy(() -> as(f.operator(), () -> profileService.putProfile(f.partner().id(), "P", "UTC", null,
                List.of()))).isInstanceOf(InvalidTenantTierException.class);
        assertThatThrownBy(() -> as(f.clientB(), () -> profileService.putProfile(f.clientA().id(), "B", "UTC", null,
                List.of()))).isInstanceOf(TenantNotFoundException.class);
        assertThatThrownBy(() -> profileService.putProfile(f.clientA().id(), "B", "UTC", null, List.of()))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void putCatalog_roundTripsPricesAndAttributes_andKeepsTheNotDeclaredRule() {
        List<CatalogItem> items = List.of(
                new CatalogItem("bravas", "Tapas", "Patatas bravas", "Con salsa brava", new BigDecimal("5.50"),
                        Map.of("allergens", List.of("huevo"), "vegetariano", true, "raciones", 1)),
                new CatalogItem("tarta-queso", "Postres", "Tarta de queso", null, new BigDecimal("5.00"), Map.of()));
        as(f.clientA(), () -> catalogService.putCatalog(f.clientA().id(), List.of("allergens"), items));

        Catalog read = as(f.clientA(), () -> catalogService.findCatalog(f.clientA().id())).orElseThrow();

        assertThat(read.items()).containsExactlyElementsOf(items);
        assertThat(read.items().get(0).price()).isEqualByComparingTo("5.50");
        assertThat(read.statedAttribute(read.items().get(1), "allergens")).contains(Catalog.NOT_DECLARED);
        assertThat(countAs("catalogs", f.clientB().id())).isZero();
        assertThat(new JdbcTemplate(adminDataSource).queryForObject("SELECT payload::text FROM audit_outbox "
                + "WHERE event_type = 'admin.catalog.updated'", String.class))
                .contains("\"item_count\": 2").doesNotContain("bravas");
    }

    @Test
    void unansweredQuestions_areRecordedForTheBusiness_paginated_andWindowedByTime() {
        Instant before = clock.instant();
        List<KnowledgeGap> recorded = as(f.clientA(), () -> List.of(
                gapService.recordUnansweredQuestion(f.clientA().id(), f.agent().id(), f.conversation().id(), "¿Wifi?"),
                gapService.recordUnansweredQuestion(f.clientA().id(), f.agent().id(), f.conversation().id(), "¿Parking?"),
                gapService.recordUnansweredQuestion(f.clientA().id(), f.agent().id(), f.conversation().id(), "¿Música?")));

        List<KnowledgeGap> firstPage = as(f.partner(), () -> gapService.listForTenant(f.clientA().id(), null, 2));
        List<KnowledgeGap> secondPage = as(f.partner(), () -> gapService.listForTenant(f.clientA().id(),
                firstPage.get(1).id(), 2));

        assertThat(firstPage).extracting(KnowledgeGap::question).containsExactly("¿Wifi?", "¿Parking?");
        assertThat(secondPage).extracting(KnowledgeGap::question).containsExactly("¿Música?");
        assertThat(as(f.clientA(), () -> gapService.listRecordedBetween(f.clientA().id(), before, clock.instant())))
                .containsExactlyElementsOf(recorded);
        assertThat(as(f.clientB(), () -> gapService.listForTenant(f.clientA().id(), null, 10))).isEmpty();
    }
}
