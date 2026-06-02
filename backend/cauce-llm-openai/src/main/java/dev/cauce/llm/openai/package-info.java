/**
 * Cauce OpenAI-compatible LLM adapter: a single {@link dev.cauce.llm.spi.LlmProvider}
 * implementation that speaks the OpenAI {@code /chat/completions} wire format, reused for OpenAI,
 * Mistral, and a locally hosted Ollama.
 *
 * <p>Endpoints are selected by configuration
 * ({@code cauce.llm.openai-compatible.providers.<id>.*}); the agent's {@code modelProvider} chooses
 * which registered provider serves a given invocation. No vendor SDK — JDK
 * {@link java.net.http.HttpClient} plus Jackson. Provider-specific concepts never leak into
 * {@code cauce-core} (architectural invariant #3).
 */
package dev.cauce.llm.openai;
