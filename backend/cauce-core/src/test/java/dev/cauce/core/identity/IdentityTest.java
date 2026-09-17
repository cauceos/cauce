package dev.cauce.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void create_generatesUuidV7() {
        assertThat(newIdentity().id().version()).isEqualTo(7);
    }

    @Test
    void create_assignsFields() {
        Identity identity = Identity.create(TENANT, "telegram", IdentityKind.PROVIDER_USER_ID, "987654321");

        assertThat(identity.tenantId()).isEqualTo(TENANT);
        assertThat(identity.channelType()).isEqualTo("telegram");
        assertThat(identity.kind()).isEqualTo(IdentityKind.PROVIDER_USER_ID);
        assertThat(identity.value()).isEqualTo("987654321");
        assertThat(identity.createdAt()).isNotNull();
    }

    @Test
    void create_stripsChannelTypeAndValue_withoutOtherwiseInterpretingThem() {
        Identity identity = Identity.create(TENANT, " whatsapp ", IdentityKind.PHONE_NUMBER, " +34 612 345 678 ");

        assertThat(identity.channelType()).isEqualTo("whatsapp");
        // Inner spacing is preserved: the core does not normalise phone numbers.
        assertThat(identity.value()).isEqualTo("+34 612 345 678");
    }

    @Test
    void create_rejectsNullTenantId() {
        assertThatThrownBy(() -> Identity.create(null, "api", IdentityKind.CLIENT_REFERENCE, "me"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_rejectsBlankChannelType() {
        assertThatThrownBy(() -> Identity.create(TENANT, "  ", IdentityKind.CLIENT_REFERENCE, "me"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelType");
    }

    @Test
    void create_rejectsNullKind() {
        assertThatThrownBy(() -> Identity.create(TENANT, "api", null, "me"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_rejectsNullValue() {
        assertThatThrownBy(() -> Identity.create(TENANT, "api", IdentityKind.CLIENT_REFERENCE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value");
    }

    @Test
    void create_rejectsBlankValue() {
        assertThatThrownBy(() -> Identity.create(TENANT, "api", IdentityKind.CLIENT_REFERENCE, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rehydrate_preservesEveryField() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-17T10:00:00Z");

        Identity identity = Identity.rehydrate(id, TENANT, "email", IdentityKind.EMAIL_ADDRESS,
                "someone@example.org", createdAt);

        assertThat(identity.id()).isEqualTo(id);
        assertThat(identity.tenantId()).isEqualTo(TENANT);
        assertThat(identity.channelType()).isEqualTo("email");
        assertThat(identity.kind()).isEqualTo(IdentityKind.EMAIL_ADDRESS);
        assertThat(identity.value()).isEqualTo("someone@example.org");
        assertThat(identity.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void rehydrate_rejectsNullCreatedAt() {
        assertThatThrownBy(() -> Identity.rehydrate(UUID.randomUUID(), TENANT, "email",
                IdentityKind.EMAIL_ADDRESS, "someone@example.org", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalsAndHashCode_areBasedOnId() {
        Identity a = newIdentity();
        Identity sameId = Identity.rehydrate(a.id(), UUID.randomUUID(), "voice", IdentityKind.PHONE_NUMBER,
                "+34999", a.createdAt());
        Identity different = newIdentity();

        assertThat(a).isEqualTo(sameId);
        assertThat(a).hasSameHashCodeAs(sameId);
        assertThat(a).isNotEqualTo(different);
    }

    @Test
    void toString_omitsTheValue() {
        Identity identity = Identity.create(TENANT, "whatsapp", IdentityKind.PHONE_NUMBER, "+34612345678");

        assertThat(identity.toString())
                .contains(identity.id().toString())
                .contains("whatsapp")
                .contains("PHONE_NUMBER")
                .doesNotContain("+34612345678");
    }

    @Test
    void kind_hasTheFiveValuesOfTheStartingVocabulary() {
        assertThat(IdentityKind.values()).containsExactly(
                IdentityKind.PHONE_NUMBER,
                IdentityKind.EMAIL_ADDRESS,
                IdentityKind.PROVIDER_USER_ID,
                IdentityKind.CLIENT_REFERENCE,
                IdentityKind.SESSION_ID);
    }

    private static Identity newIdentity() {
        return Identity.create(TENANT, "api", IdentityKind.CLIENT_REFERENCE, "user-" + UUID.randomUUID());
    }
}
