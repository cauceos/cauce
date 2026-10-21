package dev.cauce.core.business.profile;

import dev.cauce.core.UuidGenerator;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The profile of a business (ADR 0007, B1): its name, its time zone, the language it speaks to
 * its customers in, and the facts an agent may state about it — opening hours, address,
 * policies — as ordered key/value pairs. Sector-neutral: what the facts say is data loaded by
 * the business, never code. One per business tenant (B5).
 *
 * <p>Pure domain type, immutable. A replacement keeps the id and the creation time.
 */
public final class BusinessProfile {

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_FACTS = 200;
    public static final int MAX_FACT_KEY_LENGTH = 100;
    public static final int MAX_FACT_VALUE_LENGTH = 2000;
    public static final int MAX_HANDOFF_CATEGORIES = 20;
    /** The language of a business that does not set one. */
    public static final String DEFAULT_LOCALE = "es";

    /** One fact of the profile: a label and what the business says about it. */
    public record Fact(String key, String value) {

        public Fact {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("a fact key must not be blank");
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("fact '" + key.strip() + "' must have a value");
            }
            key = key.strip();
            value = value.strip();
            if (key.length() > MAX_FACT_KEY_LENGTH) {
                throw new IllegalArgumentException("fact key '" + key + "' exceeds " + MAX_FACT_KEY_LENGTH
                        + " characters");
            }
            if (value.length() > MAX_FACT_VALUE_LENGTH) {
                throw new IllegalArgumentException("fact '" + key + "' exceeds " + MAX_FACT_VALUE_LENGTH
                        + " characters");
            }
        }
    }

    private final UUID id;
    private final UUID tenantId;
    private final String name;
    private final ZoneId timeZone;
    private final String locale;
    private final List<Fact> facts;
    private final List<String> handoffCategories;
    private final DigestSchedule dailyDigest; // null: no daily digest
    private final Instant createdAt;
    private final Instant updatedAt;

    private BusinessProfile(UUID id, UUID tenantId, String name, ZoneId timeZone, String locale,
                            List<Fact> facts, List<String> handoffCategories, DigestSchedule dailyDigest,
                            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.timeZone = timeZone;
        this.locale = locale;
        this.facts = facts;
        this.handoffCategories = handoffCategories;
        this.dailyDigest = dailyDigest;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * A new profile for {@code tenantId}. {@code timeZone} is an IANA zone id (e.g.
     * {@code Europe/Madrid}); {@code locale} a language tag, {@link #DEFAULT_LOCALE} when null. Fact
     * keys are unique.
     */
    public static BusinessProfile create(UUID tenantId, String name, String timeZone, String locale,
                                         List<Fact> facts) {
        Instant now = Instant.now();
        return new BusinessProfile(UuidGenerator.newV7(), Objects.requireNonNull(tenantId, "tenantId"),
                validatedName(name), validatedZone(timeZone), validatedLocale(locale), validatedFacts(facts),
                List.of(), null, now, now);
    }

    /** This profile with its content replaced: same id, tenant and creation time; updated now. */
    public BusinessProfile replacedBy(String name, String timeZone, String locale, List<Fact> facts) {
        return new BusinessProfile(id, tenantId, validatedName(name), validatedZone(timeZone),
                validatedLocale(locale), validatedFacts(facts), handoffCategories, dailyDigest, createdAt, Instant.now());
    }

    /**
     * This profile with the categories its agents may hand cases to the owner under (ADR 0007, B7):
     * lower-cased, unique, at most {@value #MAX_HANDOFF_CATEGORIES}. Empty means any category.
     */
    public BusinessProfile withHandoffCategories(List<String> categories) {
        return new BusinessProfile(id, tenantId, name, timeZone, locale, facts, validatedCategories(categories),
                dailyDigest, createdAt, updatedAt);
    }

    /**
     * When the business wants its nightly summary (ADR 0007, B10): the local time it is sent at and
     * whether a day with nothing to report is skipped. Null: no digest.
     */
    public record DigestSchedule(LocalTime localTime, boolean skipWhenEmpty) {

        public DigestSchedule {
            Objects.requireNonNull(localTime, "localTime");
            localTime = localTime.withSecond(0).withNano(0);
        }
    }

    /** This profile with {@code dailyDigest} as its nightly summary setting; null turns it off. */
    public BusinessProfile withDigestSchedule(DigestSchedule dailyDigest) {
        return new BusinessProfile(id, tenantId, name, timeZone, locale, facts, handoffCategories, dailyDigest,
                createdAt, updatedAt);
    }

    /** Rebuilds a persisted profile. For the persistence layer only. */
    public static BusinessProfile rehydrate(UUID id, UUID tenantId, String name, String timeZone, String locale,
                                            List<Fact> facts, List<String> handoffCategories,
                                            DigestSchedule dailyDigest, Instant createdAt, Instant updatedAt) {
        return new BusinessProfile(Objects.requireNonNull(id, "id"), Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(name, "name"), ZoneId.of(timeZone), Objects.requireNonNull(locale, "locale"),
                List.copyOf(facts), List.copyOf(handoffCategories), dailyDigest,
                Objects.requireNonNull(createdAt, "createdAt"), Objects.requireNonNull(updatedAt, "updatedAt"));
    }

    private static String validatedName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        String stripped = name.strip();
        if (stripped.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return stripped;
    }

    private static ZoneId validatedZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            throw new IllegalArgumentException("time_zone must not be blank");
        }
        try {
            return ZoneId.of(timeZone.strip());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("time_zone '" + timeZone + "' is not a known time zone");
        }
    }

    private static String validatedLocale(String locale) {
        if (locale == null) {
            return DEFAULT_LOCALE;
        }
        Locale parsed = Locale.forLanguageTag(locale.strip());
        if (parsed.getLanguage().isEmpty()) {
            throw new IllegalArgumentException("locale '" + locale + "' is not a language tag");
        }
        return parsed.toLanguageTag();
    }

    private static List<Fact> validatedFacts(List<Fact> facts) {
        Objects.requireNonNull(facts, "facts");
        if (facts.size() > MAX_FACTS) {
            throw new IllegalArgumentException("a profile holds at most " + MAX_FACTS + " facts");
        }
        Set<String> keys = new HashSet<>();
        for (Fact fact : facts) {
            Objects.requireNonNull(fact, "fact");
            if (!keys.add(fact.key().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("fact '" + fact.key() + "' appears twice");
            }
        }
        return List.copyOf(facts);
    }

    private static List<String> validatedCategories(List<String> categories) {
        if (categories == null) {
            return List.of();
        }
        if (categories.size() > MAX_HANDOFF_CATEGORIES) {
            throw new IllegalArgumentException("at most " + MAX_HANDOFF_CATEGORIES + " handoff categories");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String category : categories) {
            if (category == null || category.isBlank() || category.strip().length() > 40) {
                throw new IllegalArgumentException("a handoff category must be 1 to 40 characters");
            }
            if (!unique.add(category.strip().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("handoff category '" + category.strip() + "' appears twice");
            }
        }
        return List.copyOf(unique);
    }

    /** Whether an agent may hand a case over under {@code category}: any, when none are listed. */
    public boolean acceptsHandoffCategory(String category) {
        return handoffCategories.isEmpty()
                || category != null && handoffCategories.contains(category.strip().toLowerCase(Locale.ROOT));
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String name() {
        return name;
    }

    public ZoneId timeZone() {
        return timeZone;
    }

    public String locale() {
        return locale;
    }

    public List<Fact> facts() {
        return facts;
    }

    public List<String> handoffCategories() {
        return handoffCategories;
    }

    /** The nightly summary setting; empty when the business has none. */
    public Optional<DigestSchedule> digestSchedule() {
        return Optional.ofNullable(dailyDigest);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof BusinessProfile other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "BusinessProfile[id=%s, tenantId=%s, timeZone=%s, facts=%d]"
                .formatted(id, tenantId, timeZone, facts.size());
    }
}
