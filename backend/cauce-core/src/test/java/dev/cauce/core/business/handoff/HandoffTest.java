package dev.cauce.core.business.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.profile.BusinessProfile;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HandoffTest {

    private final Booking.Requester requester = new Booking.Requester(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test
    void open_isOpenForTheRequestersConversation_withALowerCasedCategory() {
        Handoff handoff = Handoff.open(requester, new Handoff.Request(" Evento ", "25 personas", "Marta", " "));

        assertThat(handoff.status()).isEqualTo(Handoff.Status.OPEN);
        assertThat(handoff.category()).isEqualTo("evento");
        assertThat(handoff.contactPhone()).isEmpty();
        assertThat(handoff.conversationId()).isEqualTo(requester.conversationId());
        assertThat(handoff.identityId()).isEqualTo(requester.identityId());
        assertThat(handoff.code()).hasSize(Booking.CODE_LENGTH);
        assertThat(handoff.resolvedAt()).isEmpty();
    }

    @Test
    void resolved_isFinal_andKeepsItsFirstResolution() {
        Handoff resolved = Handoff.open(requester, new Handoff.Request("queja", "Fría", "Luis", null)).resolved();

        assertThat(resolved.status()).isEqualTo(Handoff.Status.RESOLVED);
        assertThat(resolved.resolved().resolvedAt()).isEqualTo(resolved.resolvedAt());
    }

    @Test
    void request_requiresCategorySummaryAndName() {
        assertThatThrownBy(() -> new Handoff.Request(" ", "s", "n", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Handoff.Request("c", "x".repeat(Handoff.MAX_SUMMARY_LENGTH + 1), "n", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Handoff.Request("c", "s", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aProfileAcceptsAnyCategoryUntilItListsSome() {
        BusinessProfile profile = BusinessProfile.create(UUID.randomUUID(), "Bar", "UTC", null, List.of());

        assertThat(profile.acceptsHandoffCategory("boda")).isTrue();
        BusinessProfile listed = profile.withHandoffCategories(List.of("Evento", "queja"));
        assertThat(listed.handoffCategories()).containsExactly("evento", "queja");
        assertThat(listed.acceptsHandoffCategory(" EVENTO ")).isTrue();
        assertThat(listed.acceptsHandoffCategory("boda")).isFalse();
        assertThatThrownBy(() -> profile.withHandoffCategories(List.of("a", "A")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
