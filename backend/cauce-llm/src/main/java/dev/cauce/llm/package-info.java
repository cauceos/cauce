/**
 * Cauce LLM — the LLM provider SPI.
 *
 * <p>Defines the pluggable contract for LLM providers (OpenAI, Anthropic, Mistral,
 * Ollama, and others). The core never imports a specific provider; it always goes through
 * this SPI. Concrete adapters live in separate {@code cauce-llm-<provider>} modules.
 *
 * <p><b>On the framework dependency:</b> this module depends on {@code spring-context}
 * for provider registry auto-wiring ({@link dev.cauce.llm.spi.LlmProviderRegistry}) and
 * environment-based credential resolution
 * ({@link dev.cauce.llm.spi.SystemDefaultLlmCredential}). The module remains free of
 * specific LLM provider implementations (those live in {@code cauce-llm-<provider>}
 * modules). The "core stays free of provider imports" invariant is preserved at the
 * SPI/adapter boundary, not at the framework boundary.
 */
package dev.cauce.llm;
