/**
 * Cauce observability — OpenTelemetry integration, traces, metrics, and replay.
 *
 * <p>Makes the platform observable by default (invariant 4). Current content: Micrometer
 * metrics derived from the orchestration event stream
 * ({@link dev.cauce.observability.metrics}). Consumers in this module are pure: they read
 * {@code OrchestrationEvent}s and update telemetry, never influencing the business path.
 * Distributed tracing (OpenTelemetry spans), metric exporters (OTLP/Prometheus), and
 * conversation replay are planned, separate units.
 */
package dev.cauce.observability;
