package dev.cauce.api.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request body for {@code POST /v1/tenants/partner}. */
public record CreatePartnerRequest(
        @NotBlank String name,
        @NotNull UUID operatorId) {
}
