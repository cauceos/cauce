package dev.cauce.api.apikey;

/**
 * Request body for {@code POST /v1/tenants/{tenantId}/api-keys}. {@code label} is an optional human
 * name for the key; a blank or absent label is defaulted by the controller (the persisted name is
 * non-null). No expiry is accepted yet — issued keys do not expire.
 */
public record CreateApiKeyRequest(String label) {
}
