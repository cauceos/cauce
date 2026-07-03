/**
 * The invocation lifecycle event contract: a sealed hierarchy of immutable records emitted by
 * the orchestrator's agentic loop, one per observable step of an invocation's life. Purely
 * observational — emission never alters orchestrator behaviour or transaction boundaries.
 *
 * <p>Payloads are plain JDK types on purpose: this module has no dependencies at all, so
 * future consumers (observability, governance, per-tenant usage accounting) can listen
 * without depending on cauce-orchestration's internals.
 *
 * <p>The stream is at-least-once: a reprocessed invocation (e.g. after a reaper recovery)
 * re-emits its events. Consumers must be idempotent on {@code invocationId} plus position.
 */
package dev.cauce.orchestration.events;
