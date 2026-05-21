/**
 * Cauce tenancy — multi-tenant isolation, hierarchical visibility, quotas, usage tracking.
 *
 * <p>Implements the three-level tenancy model (Operator &rarr; Partner &rarr; End client)
 * with hierarchical visibility, backed by PostgreSQL Row-Level Security and a
 * {@code parent_tenant_id} relationship. Every domain entity is tenant-scoped.
 */
package dev.cauce.tenancy;
