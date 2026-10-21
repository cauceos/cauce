package dev.cauce.api.business;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * Body of {@code PUT /v1/tenants/{tenantId}/business-profile}: the whole profile, replacing any
 * previous one. {@code facts} is an object of label → text, kept in the order it is written;
 * {@code locale} is optional ({@code es} when absent). {@code handoffCategories}, optional, lists
 * the categories the business's agents may hand cases over under (empty: any). The domain
 * validates sizes and the zone.
 */
public record BusinessProfileRequest(
        @NotBlank String name,
        @NotBlank String timeZone,
        String locale,
        @NotNull Map<String, String> facts,
        List<String> handoffCategories) {
}
