package dev.cauce.business;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.business.booking.AgendaMapper;
import dev.cauce.business.booking.BookingMapper;
import dev.cauce.business.catalog.CatalogMapper;
import dev.cauce.business.gap.KnowledgeGapMapper;
import dev.cauce.business.profile.BusinessProfileMapper;
import dev.cauce.core.business.booking.Agenda;
import dev.cauce.core.business.booking.Agenda.Settings;
import dev.cauce.core.business.booking.Agenda.Shift;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.catalog.Catalog;
import dev.cauce.core.business.catalog.CatalogItem;
import dev.cauce.core.business.gap.KnowledgeGap;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfile.Fact;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        KnowledgeGap gap = KnowledgeGap.record(tenantId, UUID.randomUUID(), UUID.randomUUID(), "¿Wifi?", Instant.now());

        assertThat(mapper.toDomain(mapper.toEntity(gap))).isEqualTo(gap);
    }

    @Test
    void agendaMapper_roundTripsShiftsDaysAndClosedDates() {
        AgendaMapper mapper = new AgendaMapper();
        Agenda agenda = Agenda.create(tenantId, new Settings("comensales", 8, 10, 60, true,
                List.of(new Shift("comida", EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY), LocalTime.of(13, 0),
                        LocalTime.of(15, 30), 40)), Set.of(LocalDate.of(2026, 12, 25))));

        Agenda back = mapper.toDomain(mapper.toEntity(agenda));

        assertThat(back.settings()).isEqualTo(agenda.settings());
        assertThat(AgendaMapper.toMap(agenda.shifts().get(0))).containsEntry("days", List.of("tue", "sun"))
                .containsEntry("start", "13:00");
    }

    @Test
    void bookingMapper_roundTripsEveryField_withAndWithoutTheOptionalOnes() {
        BookingMapper mapper = new BookingMapper();
        Booking held = Booking.propose(new Booking.Requester(tenantId, UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID()),
                new Booking.Details(LocalDate.of(2026, 11, 3), "cena", LocalTime.of(21, 30), 4, "Ana", "+34 600",
                        "Terraza"),
                Instant.now().truncatedTo(ChronoUnit.MICROS), UUID.randomUUID());
        Booking requested = held.confirmed().withCancellationRequested(UUID.randomUUID(),
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        for (Booking booking : List.of(held, requested)) {
            Booking back = mapper.toDomain(mapper.toEntity(booking));
            assertThat(back).usingRecursiveComparison().isEqualTo(booking);
        }
    }
}
