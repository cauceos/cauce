package dev.cauce.core.business.catalog;

import dev.cauce.core.UuidGenerator;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The catalog of a business (ADR 0007, B1, B4): its items, and the attributes every item must
 * state — {@link #declaredAttributes()}. For a declared attribute, an item that does not state it
 * is <em>not declared</em>, never "none": an empty list is a statement ("contains none"), an
 * absent attribute is not. That is what lets an agent say "it is not stated" instead of
 * inventing an answer. One per business tenant (B5).
 *
 * <p>Pure domain type, immutable. A replacement keeps the id and the creation time.
 */
public final class Catalog {

    public static final int MAX_ITEMS = 500;
    public static final int MAX_DECLARED_ATTRIBUTES = 20;
    /** How a declared attribute an item does not state is reported. */
    public static final String NOT_DECLARED = "NOT_DECLARED";

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    /** Words too common to narrow a search, in the languages the platform speaks. */
    private static final Set<String> STOPWORDS = Set.of("con", "sin", "los", "las", "del", "que", "una", "uno",
            "unos", "unas", "por", "para", "the", "and", "with", "without", "for", "les", "des", "avec", "sans");

    private final UUID id;
    private final UUID tenantId;
    private final List<String> declaredAttributes;
    private final List<CatalogItem> items;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Catalog(UUID id, UUID tenantId, List<String> declaredAttributes, List<CatalogItem> items,
                    Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.declaredAttributes = declaredAttributes;
        this.items = items;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A new catalog for {@code tenantId}. Item refs are unique; declared attribute names too. */
    public static Catalog create(UUID tenantId, List<String> declaredAttributes, List<CatalogItem> items) {
        Instant now = Instant.now();
        return new Catalog(UuidGenerator.newV7(), Objects.requireNonNull(tenantId, "tenantId"),
                validatedDeclared(declaredAttributes), validatedItems(items), now, now);
    }

    /** This catalog with its content replaced: same id, tenant and creation time; updated now. */
    public Catalog replacedBy(List<String> declaredAttributes, List<CatalogItem> items) {
        return new Catalog(id, tenantId, validatedDeclared(declaredAttributes), validatedItems(items), createdAt,
                Instant.now());
    }

    /** Rebuilds a persisted catalog. For the persistence layer only. */
    public static Catalog rehydrate(UUID id, UUID tenantId, List<String> declaredAttributes, List<CatalogItem> items,
                                    Instant createdAt, Instant updatedAt) {
        return new Catalog(Objects.requireNonNull(id, "id"), Objects.requireNonNull(tenantId, "tenantId"),
                List.copyOf(declaredAttributes), List.copyOf(items), Objects.requireNonNull(createdAt, "createdAt"),
                Objects.requireNonNull(updatedAt, "updatedAt"));
    }

    /** The sections of the catalog, in the order their first item appears. */
    public List<String> sections() {
        Set<String> sections = new LinkedHashSet<>();
        items.forEach(item -> sections.add(item.section()));
        return List.copyOf(sections);
    }

    /**
     * The value of {@code attribute} for {@code item} as an agent must read it: the stated value,
     * {@link #NOT_DECLARED} for a declared attribute the item does not state, empty for an
     * undeclared attribute the item does not state.
     */
    public Optional<Object> statedAttribute(CatalogItem item, String attribute) {
        Optional<Object> value = item.attribute(attribute);
        if (value.isPresent() || !declaredAttributes.contains(attribute)) {
            return value;
        }
        return Optional.of(NOT_DECLARED);
    }

    /**
     * Searches the catalog. Each significant word of {@code text} is looked for at the start of
     * the words of an item's name, section, description, ref and attribute values (case, accents
     * and a plural ending ignored); the items matching the most words are returned, so a question
     * worded naturally still finds its dish, and a text no item matches returns none. A blank
     * {@code text} matches every item. {@code section}, when given, keeps the items of that
     * section.
     *
     * <p>{@code withoutAttribute}/{@code withoutValue}, when given, keep only the items that state
     * the attribute and do not contain the value. An item that does not state it cannot be
     * vouched for either way: it is returned apart, as {@code undetermined}, never as a match.
     */
    public SearchResult search(String text, String section, String withoutAttribute, String withoutValue) {
        List<String> words = significantWords(text);
        String wantedSection = section == null || section.isBlank() ? null : normalize(section);
        boolean excluding = withoutAttribute != null && !withoutAttribute.isBlank()
                && withoutValue != null && !withoutValue.isBlank();
        List<CatalogItem> candidates = new ArrayList<>();
        int best = 0;
        for (CatalogItem item : items) {
            if (wantedSection != null && !normalize(item.section()).contains(wantedSection)) {
                continue;
            }
            int score = matchedWords(item, words);
            if (score > best) {
                best = score;
                candidates.clear();
            }
            if (score == best && (score > 0 || words.isEmpty())) {
                candidates.add(item);
            }
        }
        List<CatalogItem> matches = new ArrayList<>();
        List<CatalogItem> undetermined = new ArrayList<>();
        for (CatalogItem item : candidates) {
            if (excluding) {
                Optional<Object> stated = item.attribute(withoutAttribute.strip());
                if (stated.isEmpty()) {
                    undetermined.add(item);
                    continue;
                }
                if (contains(stated.get(), withoutValue)) {
                    continue;
                }
            }
            matches.add(item);
        }
        return new SearchResult(List.copyOf(matches), List.copyOf(undetermined));
    }

    /** What a search found: the matching items, and those it cannot vouch for. */
    public record SearchResult(List<CatalogItem> matches, List<CatalogItem> undetermined) {
    }

    private static int matchedWords(CatalogItem item, List<String> words) {
        if (words.isEmpty()) {
            return 0;
        }
        StringBuilder haystack = new StringBuilder()
                .append(item.ref()).append(' ').append(item.name()).append(' ').append(item.section());
        if (item.description() != null) {
            haystack.append(' ').append(item.description());
        }
        item.attributes().values().forEach(value -> haystack.append(' ').append(value));
        Set<String> itemWords = new HashSet<>();
        for (String word : NON_WORD.split(normalize(haystack.toString()))) {
            if (!word.isEmpty()) {
                itemWords.add(stem(word));
            }
        }
        return (int) words.stream()
                .filter(word -> itemWords.stream().anyMatch(candidate -> candidate.startsWith(word)))
                .count();
    }

    private static boolean contains(Object stated, String value) {
        String wanted = normalize(value.strip());
        if (stated instanceof List<?> list) {
            return list.stream().anyMatch(element -> normalize(element.toString()).equals(wanted));
        }
        return normalize(stated.toString()).equals(wanted);
    }

    private static List<String> significantWords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (String word : NON_WORD.split(normalize(text))) {
            if (word.length() > 2 && !STOPWORDS.contains(word)) {
                words.add(stem(word));
            }
        }
        return words;
    }

    /** Drops a plural ending so "croquetas" finds "croqueta" and "tomatoes" finds "tomato". */
    private static String stem(String word) {
        if (word.length() > 4 && word.endsWith("es")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.length() > 3 && word.endsWith("s")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static String normalize(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static List<String> validatedDeclared(List<String> declaredAttributes) {
        if (declaredAttributes == null) {
            return List.of();
        }
        if (declaredAttributes.size() > MAX_DECLARED_ATTRIBUTES) {
            throw new IllegalArgumentException("at most " + MAX_DECLARED_ATTRIBUTES + " declared attributes");
        }
        Set<String> names = new LinkedHashSet<>();
        for (String name : declaredAttributes) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a declared attribute name must not be blank");
            }
            if (!names.add(name.strip())) {
                throw new IllegalArgumentException("declared attribute '" + name.strip() + "' appears twice");
            }
        }
        return List.copyOf(names);
    }

    private static List<CatalogItem> validatedItems(List<CatalogItem> items) {
        Objects.requireNonNull(items, "items");
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("a catalog holds at most " + MAX_ITEMS + " items");
        }
        Set<String> refs = new HashSet<>();
        for (CatalogItem item : items) {
            Objects.requireNonNull(item, "item");
            if (!refs.add(item.ref())) {
                throw new IllegalArgumentException("item ref '" + item.ref() + "' appears twice");
            }
        }
        return List.copyOf(items);
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public List<String> declaredAttributes() {
        return declaredAttributes;
    }

    public List<CatalogItem> items() {
        return items;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Catalog other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Catalog[id=%s, tenantId=%s, items=%d]".formatted(id, tenantId, items.size());
    }
}
