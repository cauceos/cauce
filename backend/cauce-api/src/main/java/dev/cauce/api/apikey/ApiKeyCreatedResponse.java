package dev.cauce.api.apikey;

import dev.cauce.core.apikey.ApiKey;
import dev.cauce.tenancy.ApiKeyCreationResult;
import java.time.Instant;
import java.util.UUID;

/**
 * 201 response for minting an API key. Carries the {@code apiKey} plaintext EXACTLY ONCE — it is
 * unrecoverable afterwards — alongside the new key's metadata. Serialised in snake_case.
 */
public record ApiKeyCreatedResponse(
        String apiKey,
        UUID id,
        UUID tenantId,
        String keyPrefix,
        String label,
        String status,
        Instant createdAt) {

    public static ApiKeyCreatedResponse from(ApiKeyCreationResult result) {
        ApiKey key = result.apiKey();
        return new ApiKeyCreatedResponse(
                result.plaintextKey(),
                key.id(),
                key.tenantId(),
                key.keyPrefix(),
                key.name(),
                ApiKeyResponse.status(key),
                key.createdAt());
    }
}
