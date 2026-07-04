/**
 * Cauce channels — the channel adapter SPI and reference adapters.
 *
 * <p>Defines the pluggable contract that channels must fulfill (invariant 3): the inbound
 * half ({@code spi.InboundChannelAdapter}: provider webhook → neutral
 * {@code ChannelInboundMessage} → the existing ingest boundary) and the outbound half
 * ({@code spi.OutboundChannelAdapter}: neutral {@code ChannelOutboundMessage} → provider
 * delivery). {@code config.ChannelConfig} binds a channel instance to an agent/tenant.
 * First reference adapter: Telegram (inbound). Provider-specific concepts must never leak
 * into {@code cauce-core}.
 */
package dev.cauce.channels;
