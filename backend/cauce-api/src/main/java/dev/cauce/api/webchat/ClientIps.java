package dev.cauce.api.webchat;

import dev.cauce.channels.webchat.WebChatProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The visitor's IP as the request limits see it (ADR 0014, WC3 and amendment): only an HMAC of it, under a
 * key outside the database. The IP itself is never returned, stored or logged. It is
 * {@code CF-Connecting-IP} when the request comes from a declared trusted proxy, and the socket's address
 * otherwise.
 */
@Component
public class ClientIps {

    private static final Logger log = LoggerFactory.getLogger(ClientIps.class);
    static final String CF_CONNECTING_IP = "CF-Connecting-IP";

    private final byte[] key;
    private final Set<String> trustedProxies;

    public ClientIps(WebChatProperties properties) {
        if (properties.getIpHashKey() == null || properties.getIpHashKey().isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
            log.warn("cauce.web.ip-hash-key is not set: the web chat's per-IP limits use a key of this process "
                    + "only, so each instance counts on its own. Set it for more than one instance.");
        } else {
            this.key = Base64.getDecoder().decode(properties.getIpHashKey().strip());
        }
        this.trustedProxies = Set.copyOf(properties.getTrustedProxies());
    }

    /** The HMAC of the request's visitor IP, hex. */
    public String hashOf(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        String forwarded = request.getHeader(CF_CONNECTING_IP);
        if (forwarded != null && !forwarded.isBlank() && trustedProxies.contains(ip)) {
            ip = forwarded.strip();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(ip.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
