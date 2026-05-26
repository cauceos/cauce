package dev.cauce.tenancy;

import dev.cauce.core.apikey.ApiKey;

/**
 * Outcome of {@link ApiKeyService#createApiKey}: the persisted {@link ApiKey} (which
 * never carries the plaintext) and the {@code plaintextKey} itself, exposed exactly
 * once at creation time. Callers must surface the plaintext to the end user
 * immediately; it cannot be recovered once the response returns.
 */
public record ApiKeyCreationResult(ApiKey apiKey, String plaintextKey) {
}
