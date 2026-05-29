package dev.cauce.api.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request body for {@code POST /v1/tenants/client}. */
public record CreateClientRequest(
        @NotBlank String name,
        @NotNull UUID partnerId) {
}
