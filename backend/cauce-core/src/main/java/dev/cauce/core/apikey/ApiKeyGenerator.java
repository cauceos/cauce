package dev.cauce.core.apikey;

import java.security.SecureRandom;

/**
 * Mints fresh API keys with a fixed, recognisable shape.
 *
 * <p>Format: {@value #PREFIX} followed by {@value #RANDOM_PART_LENGTH} alphanumeric
 * characters drawn from {@code [0-9a-zA-Z]}. Total length is {@value #TOTAL_LENGTH}
 * characters, e.g. {@code ck_a3f5d2c1b8e9f0a1d2c3b4e5f6789abc}.
 *
 * <p>The {@code ck_} prefix identifies a "Cauce Key" at a glance in logs and code
 * search — useful for finding accidentally-pasted secrets — and gives the
 * {@link dev.cauce.core.apikey.ApiKey#keyPrefix()} field a stable, non-secret slice
 * for visual identification. The random suffix is 62<sup>32</sup> &asymp; 2.27e57
 * possibilities, which puts brute-force forgery comfortably out of reach.
 */
public final class ApiKeyGenerator {

    public static final String PREFIX = "ck_";
    public static final int RANDOM_PART_LENGTH = 32;
    public static final int TOTAL_LENGTH = PREFIX.length() + RANDOM_PART_LENGTH;

    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private ApiKeyGenerator() {
    }

    /** Returns a new key with the format above. */
    public static String newKey() {
        char[] buf = new char[TOTAL_LENGTH];
        PREFIX.getChars(0, PREFIX.length(), buf, 0);
        for (int i = 0; i < RANDOM_PART_LENGTH; i++) {
            buf[PREFIX.length() + i] = ALPHABET[RNG.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }

    /**
     * Returns whether {@code key} matches the format produced by {@link #newKey()}. The
     * filter uses this as a cheap pre-check before consulting the database; a key that
     * does not match is rejected without a lookup.
     */
    public static boolean isValidFormat(String key) {
        if (key == null || key.length() != TOTAL_LENGTH) {
            return false;
        }
        if (!key.startsWith(PREFIX)) {
            return false;
        }
        for (int i = PREFIX.length(); i < key.length(); i++) {
            char c = key.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }
        return true;
    }
}
