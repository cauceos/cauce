package dev.cauce.core.business.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.business.profile.BusinessProfile.Fact;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BusinessProfileTest {

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void create_stripsAndKeepsTheFactsInOrder_withTheDefaultLocale() {
        BusinessProfile profile = BusinessProfile.create(tenantId, " Bar La Esquina ", "Europe/Madrid", null,
                List.of(new Fact(" horario ", " mar-dom "), new Fact("terraza", "sí")));

        assertThat(profile.name()).isEqualTo("Bar La Esquina");
        assertThat(profile.timeZone()).isEqualTo(ZoneId.of("Europe/Madrid"));
        assertThat(profile.locale()).isEqualTo(BusinessProfile.DEFAULT_LOCALE);
        assertThat(profile.facts()).containsExactly(new Fact("horario", "mar-dom"), new Fact("terraza", "sí"));
        assertThat(profile.id().version()).isEqualTo(7);
    }

    @Test
    void create_rejectsAnUnknownZone_aRepeatedFact_andABlankName() {
        assertThatThrownBy(() -> BusinessProfile.create(tenantId, "Bar", "Mars/Olympus", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("time zone");
        assertThatThrownBy(() -> BusinessProfile.create(tenantId, "Bar", "UTC", null,
                List.of(new Fact("Horario", "a"), new Fact("horario", "b"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> BusinessProfile.create(tenantId, " ", "UTC", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fact("wifi", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replacedBy_keepsIdentityAndCreation_andReplacesTheContent() {
        BusinessProfile profile = BusinessProfile.create(tenantId, "Bar", "UTC", "es", List.of(new Fact("a", "b")));

        BusinessProfile replaced = profile.replacedBy("Bar 2", "Europe/Lisbon", "pt", List.of());

        assertThat(replaced).isEqualTo(profile);
        assertThat(replaced.createdAt()).isEqualTo(profile.createdAt());
        assertThat(replaced.name()).isEqualTo("Bar 2");
        assertThat(replaced.locale()).isEqualTo("pt");
        assertThat(replaced.facts()).isEmpty();
    }

    @Test
    void aDigestSchedule_isOffByDefault_truncatedToTheMinute_andSurvivesAReplacement() {
        BusinessProfile profile = BusinessProfile.create(tenantId, "Bar", "Europe/Madrid", null, List.of());

        assertThat(profile.digestSchedule()).isEmpty();
        BusinessProfile scheduled = profile.withDigestSchedule(
                new BusinessProfile.DigestSchedule(java.time.LocalTime.of(22, 30, 15), true));
        assertThat(scheduled.digestSchedule()).contains(
                new BusinessProfile.DigestSchedule(java.time.LocalTime.of(22, 30), true));
        assertThat(scheduled.replacedBy("Bar", "UTC", null, List.of()).digestSchedule()).isPresent();
        assertThat(scheduled.withDigestSchedule(null).digestSchedule()).isEmpty();
    }
}
