package dev.cauce.core.apikey;

/**
 * Port for one-way hashing and verification of API key plaintext.
 *
 * <p>The domain knows it must hash an API key before storing it and verify a candidate
 * against a stored hash, but it does not know <i>how</i>. The hashing algorithm
 * (bcrypt today; could be argon2 or scrypt later) is an infrastructure concern owned
 * by an adapter in cauce-tenancy. Keeping the algorithm out of cauce-core preserves
 * the hexagonal invariant that the core has no framework or library dependencies
 * beyond a UUID generator.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Be deterministic in verification ({@code matches(plaintext, hash(plaintext))} is
 *       always true), but non-deterministic in production ({@code hash(plaintext)}
 *       returns a different value each call due to a fresh salt).</li>
 *   <li>Be safe for concurrent use from multiple threads.</li>
 *   <li>Run in time that does not depend on the contents of {@code plaintext} (a
 *       constant-time comparator inside {@code matches} is standard for bcrypt).</li>
 * </ul>
 */
public interface ApiKeyHasher {

    /** Returns a fresh hash of {@code plaintext}; never returns null. */
    String hash(String plaintext);

    /** Returns whether {@code plaintext} hashes to {@code hash}. */
    boolean matches(String plaintext, String hash);
}
