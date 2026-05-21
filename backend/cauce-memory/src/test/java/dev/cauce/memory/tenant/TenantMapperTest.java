package dev.cauce.memory.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.Tier;
import org.junit.jupiter.api.Test;

class TenantMapperTest {

    private final TenantMapper mapper = new TenantMapper();

    @Test
    void toEntity_copiesAllFields() {
        Tenant operator = Tenant.operator("Acme");
        Tenant partner = Tenant.partner("Partner Co", operator.id());

        TenantEntity entity = mapper.toEntity(partner);

        assertThat(entity.getId()).isEqualTo(partner.id());
        assertThat(entity.getParentTenantId()).isEqualTo(operator.id());
        assertThat(entity.getTier()).isEqualTo(Tier.PARTNER);
        assertThat(entity.getName()).isEqualTo("Partner Co");
        assertThat(entity.getCreatedAt()).isEqualTo(partner.createdAt());
        assertThat(entity.getUpdatedAt()).isEqualTo(partner.updatedAt());
    }

    @Test
    void roundTrip_domainToEntityToDomain_preservesValue() {
        Tenant original = Tenant.operator("Acme");

        Tenant roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped.id()).isEqualTo(original.id());
        assertThat(roundTripped.parentTenantId()).isNull();
        assertThat(roundTripped.tier()).isEqualTo(Tier.OPERATOR);
        assertThat(roundTripped.name()).isEqualTo(original.name());
        assertThat(roundTripped.createdAt()).isEqualTo(original.createdAt());
        assertThat(roundTripped.updatedAt()).isEqualTo(original.updatedAt());
        assertThat(roundTripped).isEqualTo(original);
    }
}
