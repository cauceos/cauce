package dev.cauce.api.business;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Body of {@code POST /v1/tenants/{tenantId}/owner-link-codes}: the owner bot to link through. */
public record OwnerLinkCodeRequest(@NotNull UUID channelConfigId) {
}
