package dev.cauce.core.business.handoff;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * How to reach the person who opened a case (ADR 0014, amendment of 2026-11-10): a phone or an e-mail,
 * with its type. {@link #phone} and {@link #email} check the format of what an agent hands over, and an
 * incomplete one is refused with what is wrong, so the agent asks for it again: a contact is never
 * completed by guessing (the rule of ADR 0012 for misheard data, applied to writing). The canonical
 * constructor only bounds the value, so a contact stored before these checks still reads back.
 */
public record Contact(Type type, String value) {

    /** What kind of contact it is. */
    public enum Type {
        PHONE,
        EMAIL
    }

    public static final int MAX_LENGTH = 254;
    public static final int MIN_PHONE_DIGITS = 9;
    public static final int MAX_PHONE_DIGITS = 15;

    private static final Pattern PHONE_CHARS = Pattern.compile("[+0-9 ().-]+");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)*\\.[A-Za-z]{2,}");

    public Contact {
        Objects.requireNonNull(type, "type");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("contact must not be blank");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("contact must be at most " + MAX_LENGTH + " characters");
        }
    }

    /**
     * A phone as the person wrote it, if it is a whole number: digits, with an optional leading +, and
     * spaces, dots, dashes or brackets between them; {@value #MIN_PHONE_DIGITS} to {@value #MAX_PHONE_DIGITS}
     * digits.
     *
     * @throws IllegalArgumentException saying what is wrong, for the agent to ask again
     */
    public static Contact phone(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (!PHONE_CHARS.matcher(value).matches() || value.chars().filter(c -> c == '+').count() > 1) {
            throw new IllegalArgumentException("contact_phone '" + value + "' is not a phone number: ask the person "
                    + "for it again, and never write one yourself");
        }
        long digits = value.chars().filter(Character::isDigit).count();
        if (digits < MIN_PHONE_DIGITS || digits > MAX_PHONE_DIGITS) {
            throw new IllegalArgumentException("contact_phone '" + value + "' has " + digits + " digits, so it is "
                    + "incomplete or wrong: ask the person for the whole number, and never complete it yourself");
        }
        return new Contact(Type.PHONE, value);
    }

    /**
     * An e-mail address, if it is a whole one: a name, an {@code @} and a domain with its ending.
     *
     * @throws IllegalArgumentException saying what is wrong, for the agent to ask again
     */
    public static Contact email(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() > MAX_LENGTH || !EMAIL.matcher(value).matches()) {
            throw new IllegalArgumentException("contact_email '" + value + "' is not a complete e-mail address: "
                    + "ask the person for it again, and never complete it yourself");
        }
        return new Contact(Type.EMAIL, value);
    }
}
