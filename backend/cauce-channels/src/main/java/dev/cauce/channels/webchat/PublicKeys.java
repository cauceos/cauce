package dev.cauce.channels.webchat;

import java.security.SecureRandom;

/**
 * Mints a web chat's public key (ADR 0014, WC1): {@code pk_} and 24 random letters and digits. Not a
 * secret: it is embedded in the shop's page. It only has to be unique and unguessable enough that one
 * shop cannot stumble on another's.
 */
public final class PublicKeys {

    public static final String PREFIX = "pk_";
    public static final int RANDOM_LENGTH = 24;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicKeys() {
    }

    public static String newKey() {
        StringBuilder key = new StringBuilder(PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            key.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return key.toString();
    }

    /** Whether {@code value} has the shape of a public key, checked before any lookup. */
    public static boolean isValidFormat(String value) {
        return value != null && value.length() == PREFIX.length() + RANDOM_LENGTH && value.startsWith(PREFIX)
                && value.substring(PREFIX.length()).chars().allMatch(c -> ALPHABET.indexOf(c) >= 0);
    }
}
