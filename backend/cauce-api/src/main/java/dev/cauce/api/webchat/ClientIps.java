package dev.cauce.api.webchat;

import dev.cauce.channels.webchat.WebChatProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The visitor's IP as the request limits see it (ADR 0014, WC3 and amendment): only an HMAC of it, under a
 * key outside the database. The IP itself is never returned, stored or logged. It is
 * {@code CF-Connecting-IP} when the request comes from a declared trusted proxy, and the socket's address
 * otherwise.
 */
@Component
public class ClientIps {

    static final String CF_CONNECTING_IP = "CF-Connecting-IP";

    private final byte[] key;
    private final Set<String> trustedProxies;

    public ClientIps(WebChatProperties properties) {
        this.key = keyOf(properties.getIpHashKey());
        this.trustedProxies = Set.copyOf(properties.getTrustedProxies());
    }

    /** The key, or why the instance cannot start: like the secrets encryption key, never a fallback. */
    private static byte[] keyOf(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("cauce.web.ip-hash-key is not configured");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(configured.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("cauce.web.ip-hash-key is not valid base64", e);
        }
        if (key.length != 32) {
            throw new IllegalStateException("cauce.web.ip-hash-key must be 32 bytes, got " + key.length);
        }
        return key;
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
