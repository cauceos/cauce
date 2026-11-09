package dev.cauce.api.webchat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The public web-chat surface's paths (ADR 0014, amendment): {@code /web/v1/{publicKey}/...}. */
final class WebChatPaths {

    static final String PREFIX = "/web/v1/";
    private static final Pattern PATH = Pattern.compile("^/web/v1/([^/]+)(/.*)?$");

    /** The request attribute the filter leaves the resolved channel in. */
    static final String CHANNEL_ATTRIBUTE = "dev.cauce.api.webchat.channel";

    private WebChatPaths() {
    }

    /** The public key a path under the surface names, if it names one. */
    static Optional<String> publicKeyOf(String path) {
        Matcher m = PATH.matcher(path);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
