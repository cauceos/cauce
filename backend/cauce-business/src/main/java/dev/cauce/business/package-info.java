/**
 * Cauce business — the native business capabilities of ADR 0007.
 *
 * <p>Implements the business ports of {@code dev.cauce.core} (profile, catalog, bookings,
 * handoffs, unanswered questions) and owns the owner-notification outbox and the daily digest.
 * Everything here is sector-neutral: what makes an agent a bar's or a hairdresser's is data
 * loaded through the API.
 */
package dev.cauce.business;
