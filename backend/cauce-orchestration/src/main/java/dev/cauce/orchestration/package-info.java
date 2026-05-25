/**
 * Cauce orchestration — the asynchronous LLM invocation queue and (in later commits) the
 * worker and synchronous orchestrator that drain it.
 *
 * <p>{@link dev.cauce.orchestration.PendingInvocation} is the queue entry: a pure,
 * framework-free domain type. Its JPA mapping and Spring Data repository live in
 * {@link dev.cauce.orchestration.persistence}; the Flyway migration that creates the
 * {@code pending_invocations} table lives in cauce-memory alongside the other schema
 * migrations. {@link dev.cauce.orchestration.PendingInvocationService} enqueues and reads
 * invocations, tenant-scoped by Row-Level Security.
 */
package dev.cauce.orchestration;
