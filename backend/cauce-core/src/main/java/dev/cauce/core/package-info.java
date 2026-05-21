/**
 * Cauce core — domain model, agent runtime, conversation state, and the plugin SPI.
 *
 * <p>This module is the hexagonal domain core. It defines ports (interfaces) and must not
 * depend on HTTP, PostgreSQL, specific channels, or specific LLM providers. Adapters that
 * implement these ports live in the dedicated modules ({@code cauce-channels},
 * {@code cauce-llm}, {@code cauce-api}, ...).
 */
package dev.cauce.core;
