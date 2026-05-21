package dev.cauce.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantTest {

    @Test
    void operator_whenCreated_thenHasNoParentAndOperatorTier() {
        Tenant op = Tenant.operator("Acme Operator");

        assertThat(op.tier()).isEqualTo(Tier.OPERATOR);
        assertThat(op.parentTenantId()).isNull();
        assertThat(op.name()).isEqualTo("Acme Operator");
        assertThat(op.id()).isNotNull();
        assertThat(op.createdAt()).isNotNull();
        assertThat(op.updatedAt()).isNotNull();
    }

    @Test
    void operator_whenCreated_thenIdIsUuidV7() {
        assertThat(Tenant.operator("X").id().version()).isEqualTo(7);
    }

    @Test
    void partner_whenOperatorIdNull_thenThrows() {
        assertThatThrownBy(() -> Tenant.partner("P", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void partner_whenCreated_thenHasParentAndPartnerTier() {
        UUID operatorId = Tenant.operator("Op").id();

        Tenant partner = Tenant.partner("Partner Co", operatorId);

        assertThat(partner.tier()).isEqualTo(Tier.PARTNER);
        assertThat(partner.parentTenantId()).isEqualTo(operatorId);
    }

    @Test
    void client_whenPartnerIdNull_thenThrows() {
        assertThatThrownBy(() -> Tenant.client("C", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void client_whenCreated_thenHasParentAndClientTier() {
        UUID partnerId = Tenant.partner("P", Tenant.operator("Op").id()).id();

        Tenant client = Tenant.client("Client Co", partnerId);

        assertThat(client.tier()).isEqualTo(Tier.CLIENT);
        assertThat(client.parentTenantId()).isEqualTo(partnerId);
    }

    @Test
    void factory_whenNameBlank_thenThrows() {
        assertThatThrownBy(() -> Tenant.operator("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factory_whenNameHasSurroundingWhitespace_thenStripped() {
        assertThat(Tenant.operator("  Trimmed  ").name()).isEqualTo("Trimmed");
    }

    @Test
    void equalsAndHashCode_areBasedOnId() {
        Tenant a = Tenant.operator("A");
        Tenant sameId = Tenant.rehydrate(a.id(), null, Tier.OPERATOR, "different name",
                a.createdAt(), a.updatedAt());
        Tenant other = Tenant.operator("A");

        assertThat(a).isEqualTo(sameId);
        assertThat(a).hasSameHashCodeAs(sameId);
        assertThat(a).isNotEqualTo(other);
    }

    @Test
    void toString_doesNotThrowAndIncludesTier() {
        assertThat(Tenant.operator("Acme").toString()).contains("OPERATOR");
    }
}
