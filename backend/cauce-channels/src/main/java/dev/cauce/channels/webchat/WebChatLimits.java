package dev.cauce.channels.webchat;

import java.util.function.IntSupplier;

/**
 * A web chat's request limits (ADR 0014, WC3): how many sessions one IP may open an hour, how many
 * messages a session may send a minute and a day, how many messages one IP may send a minute, and how
 * many polls a session may make a minute. The platform's defaults come from {@link WebChatProperties};
 * a channel may set any of them lower, never higher. A null value is "the platform's default".
 */
public record WebChatLimits(Integer sessionsPerIpHour, Integer messagesPerSessionMinute,
                            Integer messagesPerSessionDay, Integer messagesPerIpMinute,
                            Integer readsPerSessionMinute) {

    /** The platform's defaults of ADR 0014, WC3. */
    public static final WebChatLimits PLATFORM = new WebChatLimits(10, 10, 100, 30, 60);

    /** No limit of its own: the platform's defaults throughout. */
    public static final WebChatLimits NONE = new WebChatLimits(null, null, null, null, null);

    /**
     * These overrides against {@code defaults}: each must be positive and no higher than its default.
     *
     * @throws IllegalArgumentException naming the first limit that is not
     */
    public WebChatLimits validatedAgainst(WebChatLimits defaults) {
        check("sessions_per_ip_hour", sessionsPerIpHour, defaults.sessionsPerIpHour());
        check("messages_per_session_minute", messagesPerSessionMinute, defaults.messagesPerSessionMinute());
        check("messages_per_session_day", messagesPerSessionDay, defaults.messagesPerSessionDay());
        check("messages_per_ip_minute", messagesPerIpMinute, defaults.messagesPerIpMinute());
        check("reads_per_session_minute", readsPerSessionMinute, defaults.readsPerSessionMinute());
        return this;
    }

    /** The limits in force: each override where there is one, the default elsewhere. */
    public WebChatLimits effective(WebChatLimits defaults) {
        return new WebChatLimits(pick(sessionsPerIpHour, defaults::sessionsPerIpHour),
                pick(messagesPerSessionMinute, defaults::messagesPerSessionMinute),
                pick(messagesPerSessionDay, defaults::messagesPerSessionDay),
                pick(messagesPerIpMinute, defaults::messagesPerIpMinute),
                pick(readsPerSessionMinute, defaults::readsPerSessionMinute));
    }

    private static Integer pick(Integer own, IntSupplier fallback) {
        return own != null ? own : fallback.getAsInt();
    }

    private static void check(String name, Integer own, Integer platform) {
        if (own == null) {
            return;
        }
        if (own <= 0 || own > platform) {
            throw new IllegalArgumentException(name + " must be between 1 and the platform's " + platform
                    + ": a channel may only lower a limit");
        }
    }
}
