package dev.cauce.channels.webchat;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What a web-chat channel needs besides its binding (ADR 0014, WC1, WC5): the origins a browser may
 * call it from, the Turnstile site key the widget shows, and the privacy settings. Immutable; the
 * factory validates the shape of each value.
 *
 * <p>An origin is exact: {@code scheme://host[:port]}, lowercase, without a path, a query or a
 * trailing slash, and {@code https} unless the host is the local machine. There are no wildcards.
 */
public record WebChatSettings(UUID channelConfigId, UUID tenantId, List<String> allowedOrigins,
                              String turnstileSiteKey, String privacyPolicyUrl, ConsentMode consentMode) {

    public static final int MAX_ORIGINS = 20;
    public static final int MAX_SITE_KEY_LENGTH = 100;
    public static final int MAX_URL_LENGTH = 500;

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    public WebChatSettings {
        Objects.requireNonNull(channelConfigId, "channelConfigId");
        Objects.requireNonNull(tenantId, "tenantId");
        allowedOrigins = normalizedOrigins(allowedOrigins);
        turnstileSiteKey = required(turnstileSiteKey, "turnstile_site_key", MAX_SITE_KEY_LENGTH);
        privacyPolicyUrl = validatedUrl(privacyPolicyUrl);
        Objects.requireNonNull(consentMode, "consentMode");
    }

    /** Whether a browser on {@code origin} may call this channel. */
    public boolean allows(String origin) {
        return origin != null && allowedOrigins.contains(origin.toLowerCase(Locale.ROOT));
    }

    /** {@code origin} in the exact form compared against, or an error saying what is wrong with it. */
    public static String normalizedOrigin(String origin) {
        URI uri = parse(origin, "allowed origin");
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("allowed origin '" + origin + "' must be scheme://host[:port], "
                    + "with no path, query or credentials");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        requireSecure(uri.getScheme().toLowerCase(Locale.ROOT), host, "allowed origin '" + origin + "'");
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    private static List<String> normalizedOrigins(List<String> origins) {
        if (origins == null || origins.isEmpty()) {
            throw new IllegalArgumentException("allowed_origins must name at least one origin");
        }
        if (origins.size() > MAX_ORIGINS) {
            throw new IllegalArgumentException("allowed_origins holds at most " + MAX_ORIGINS + " origins");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String origin : origins) {
            normalized.add(normalizedOrigin(origin));
        }
        return List.copyOf(normalized);
    }

    private static String validatedUrl(String url) {
        String value = required(url, "privacy_policy_url", MAX_URL_LENGTH);
        URI uri = parse(value, "privacy_policy_url");
        requireSecure(uri.getScheme().toLowerCase(Locale.ROOT), uri.getHost().toLowerCase(Locale.ROOT),
                "privacy_policy_url");
        return value;
    }

    private static URI parse(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        try {
            URI uri = new URI(value.strip());
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException(field + " '" + value + "' must be an absolute http(s) URL");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(field + " '" + value + "' is not a URL");
        }
    }

    private static void requireSecure(String scheme, String host, String what) {
        boolean local = LOCAL_HOSTS.contains(host);
        if (!scheme.equals("https") && !(scheme.equals("http") && local)) {
            throw new IllegalArgumentException(what + " must use https (http only on the local machine)");
        }
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return stripped;
    }
}
