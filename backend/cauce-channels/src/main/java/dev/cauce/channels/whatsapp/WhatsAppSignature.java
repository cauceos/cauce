package dev.cauce.channels.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The {@code X-Hub-Signature-256} check of a WhatsApp webhook (ADR 0006, W2):
 * {@code "sha256=" + lowercase_hex(HMAC-SHA256(app_secret, raw_body))}, compared in constant time.
 * Computed over the bytes exactly as received, never over a decoded and re-encoded body.
 */
final class WhatsAppSignature {

    static final String HEADER = "X-Hub-Signature-256";
    private static final String PREFIX = "sha256=";

    private WhatsAppSignature() {
    }

    /** Whether {@code headerValue} is the signature of {@code rawBody} under {@code appSecret}. */
    static boolean matches(String headerValue, byte[] rawBody, String appSecret) {
        if (headerValue == null || !headerValue.startsWith(PREFIX)) {
            return false;
        }
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(headerValue.substring(PREFIX.length()).strip().toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(presented, hmac(rawBody, appSecret));
    }

    /** {@code "sha256=" + hex}: what Meta would send for {@code rawBody}. */
    static String sign(byte[] rawBody, String appSecret) {
        return PREFIX + HexFormat.of().formatHex(hmac(rawBody, appSecret));
    }

    private static byte[] hmac(byte[] rawBody, String appSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(rawBody);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is mandatory on every JVM", e);
        }
    }
}
