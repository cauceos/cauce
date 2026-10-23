package dev.cauce.api.usage;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Request body for {@code PUT /v1/tenants/{id}/spend-limit}: US$ per calendar month in UTC. */
public record SetSpendLimitRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
        BigDecimal monthlyLimitUsd) {
}
