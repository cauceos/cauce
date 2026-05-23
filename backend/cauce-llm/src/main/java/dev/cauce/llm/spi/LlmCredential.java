package dev.cauce.llm.spi;

import java.util.Optional;

/**
 * Credential supplied to a provider at invocation time. Passing the credential as part of
 * the request (rather than baking it into the adapter) keeps the same SPI signature usable
 * when per-tenant credentials arrive (Phase 2): only the credential source changes, not
 * the contract.
 */
public interface LlmCredential {

    /** The provider API key. */
    String getApiKey();

    /** Optional organization identifier (used by some providers, e.g. OpenAI; empty for Anthropic). */
    Optional<String> getOrganizationId();
}
