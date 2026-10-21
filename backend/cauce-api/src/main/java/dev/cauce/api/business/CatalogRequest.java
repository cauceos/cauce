package dev.cauce.api.business;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Body of {@code PUT /v1/tenants/{tenantId}/catalog}: the whole catalog, replacing any previous
 * one. {@code declaredAttributes} names the attributes every item must state (ADR 0007, B4): an
 * item that leaves one out is reported as not declared, never as "none". The domain validates
 * sizes, unique refs and attribute shapes.
 */
public record CatalogRequest(
        List<@NotBlank String> declaredAttributes,
        @NotNull List<@Valid @NotNull Item> items) {

    /** One item; {@code description} and {@code price} are optional. */
    public record Item(
            @NotBlank String ref,
            @NotBlank String section,
            @NotBlank String name,
            String description,
            @PositiveOrZero BigDecimal price,
            Map<String, Object> attributes) {
    }
}
