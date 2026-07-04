package dev.cauce.channels.config;

/**
 * Lifecycle of a channel binding. Part of the immutable domain model, hence an enum
 * (unlike {@code channelType}, which is SPI-bound and stays a String).
 */
public enum ChannelConfigStatus {

    /** Bound and receiving: the webhook resolves and ingests for this config. */
    ACTIVE,

    /** Switched off: the webhook no longer resolves this config (reported as not found). */
    DISABLED
}
