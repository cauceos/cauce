/**
 * Cauce LLM — the LLM provider SPI and reference adapters.
 *
 * <p>Defines the pluggable contract for LLM providers (OpenAI, Anthropic, Mistral,
 * Ollama, and others). The core never imports a specific provider; it always goes through
 * this SPI.
 */
package dev.cauce.llm;
