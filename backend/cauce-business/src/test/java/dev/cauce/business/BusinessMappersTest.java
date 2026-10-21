package dev.cauce.business;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.business.catalog.CatalogMapper;
import dev.cauce.business.gap.KnowledgeGapMapper;
import dev.cauce.business.profile.BusinessProfileMapper;
import dev.cauce.core.business.catalog.Catalog;
import dev.cauce.core.business.catalog.CatalogItem;
import dev.cauce.core.business.gap.KnowledgeGap;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfile.Fact;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Round trips of the business mappers, nullable fields included. */
class BusinessMappersTest {

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void profileMapper_roundTripsEveryField_andTheFactOrder() {
        BusinessProfileMapper mapper = new BusinessProfileMapper();
        BusinessProfile profile = BusinessProfile.create(tenantId, "Bar", "Europe/Madrid", "es",
                List.of(new Fact("z", "último"), new Fact("a", "primero")));

        BusinessProfile back = mapper.toDomain(mapper.toEntity(profile));

        assertThat(back).isEqualTo(profile);
        assertThat(back.timeZone()).isEqualTo(profile.timeZone());
        assertThat(back.locale()).isEqualTo("es");
        assertThat(back.facts()).containsExactly(new Fact("z", "último"), new Fact("a", "primero"));
        assertThat(back.createdAt()).isEqualTo(profile.createdAt());
    }

    @Test
    void catalogMapper_roundTripsItems_withAndWithoutDescriptionAndPrice() {
        CatalogMapper mapper = new CatalogMapper();
        Catalog catalog = Catalog.create(tenantId, List.of("allergens"), List.of(
                new CatalogItem("a", "S", "Uno", "Desc", new BigDecimal("1.50"), Map.of("allergens", List.of())),
                new CatalogItem("b", "S", "Dos", null, null, Map.of())));

        Catalog back = mapper.toDomain(mapper.toEntity(catalog));

        assertThat(back.items()).containsExactlyElementsOf(catalog.items());
        assertThat(back.declaredAttributes()).containsExactly("allergens");
        assertThat(back.items().get(1).description()).isNull();
        assertThat(back.items().get(1).price()).isNull();
    }

    @Test
    void knowledgeGapMapper_roundTrips() {
        KnowledgeGapMapper mapper = new KnowledgeGapMapper();
        KnowledgeGap gap = KnowledgeGap.record(tenantId, UUID.randomUUID(), UUID.randomUUID(), "¿Wifi?");

        assertThat(mapper.toDomain(mapper.toEntity(gap))).isEqualTo(gap);
    }
}
