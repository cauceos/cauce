package dev.cauce.core.tenant;

/**
 * The three fixed levels of the Cauce tenancy hierarchy.
 *
 * <ul>
 *   <li>{@link #OPERATOR} — the entity hosting a Cauce instance (no parent).</li>
 *   <li>{@link #PARTNER} — a consultancy/agency/integrator; child of an operator.</li>
 *   <li>{@link #CLIENT} — the end business; child of a partner.</li>
 * </ul>
 */
public enum Tier {
    OPERATOR,
    PARTNER,
    CLIENT
}
