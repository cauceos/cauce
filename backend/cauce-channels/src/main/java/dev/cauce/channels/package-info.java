/**
 * Cauce channels — the channel adapter SPI and reference adapters.
 *
 * <p>Defines the pluggable contract that channels (WhatsApp, voice, email, web chat, and
 * future ones such as Telegram, Discord, RCS) must fulfill. Provider-specific concepts
 * must never leak into {@code cauce-core}.
 */
package dev.cauce.channels;
