package dev.cauce.core.business.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One item of a business catalog: a dish, a service, a product (ADR 0007, B1). {@code ref} is the
 * business's own stable reference, unique in the catalog. {@code attributes} are what the
 * business states about the item — allergens, "vegetarian", duration — each a string, a number, a
 * boolean or a list of strings; which of them must always be stated is the catalog's
 * {@link Catalog#declaredAttributes()}. {@code description} and {@code price} may be absent.
 */
public record CatalogItem(String ref, String section, String name, String description, BigDecimal price,
                          Map<String, Object> attributes) {

    public static final int MAX_REF_LENGTH = 64;
    public static final int MAX_SECTION_LENGTH = 100;
    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_DESCRIPTION_LENGTH = 1000;
    public static final int MAX_ATTRIBUTES = 30;
    public static final int MAX_ATTRIBUTE_NAME_LENGTH = 64;
    public static final int MAX_ATTRIBUTE_TEXT_LENGTH = 500;

    public CatalogItem {
        ref = required(ref, "ref", MAX_REF_LENGTH);
        section = required(section, "section of '" + ref + "'", MAX_SECTION_LENGTH);
        name = required(name, "name of '" + ref + "'", MAX_NAME_LENGTH);
        description = optional(description, "description of '" + ref + "'", MAX_DESCRIPTION_LENGTH);
        if (price != null && price.signum() < 0) {
            throw new IllegalArgumentException("price of '" + ref + "' must not be negative");
        }
        attributes = validatedAttributes(ref, attributes);
    }

    /** The value of attribute {@code name}, if the item states it. */
    public Optional<Object> attribute(String name) {
        return Optional.ofNullable(attributes.get(name));
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return stripped;
    }

    private static String optional(String value, String field, int max) {
        return value == null || value.isBlank() ? null : required(value, field, max);
    }

    private static Map<String, Object> validatedAttributes(String ref, Map<String, Object> attributes) {
        if (attributes == null) {
            return Map.of();
        }
        if (attributes.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("'" + ref + "' has more than " + MAX_ATTRIBUTES + " attributes");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            String name = required(key, "attribute name of '" + ref + "'", MAX_ATTRIBUTE_NAME_LENGTH);
            copy.put(name, validatedValue(ref, name, value));
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static Object validatedValue(String ref, String name, Object value) {
        String where = "attribute '" + name + "' of '" + ref + "'";
        if (value instanceof String text) {
            return required(text, where, MAX_ATTRIBUTE_TEXT_LENGTH);
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<String> texts = new ArrayList<>();
            for (Object element : list) {
                if (!(element instanceof String text)) {
                    throw new IllegalArgumentException(where + " must be a list of texts");
                }
                texts.add(required(text, where, MAX_ATTRIBUTE_TEXT_LENGTH));
            }
            return List.copyOf(texts);
        }
        throw new IllegalArgumentException(where + " must be a text, a number, a boolean or a list of texts");
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof CatalogItem other && ref.equals(other.ref) && section.equals(other.section)
                && name.equals(other.name) && Objects.equals(description, other.description)
                && (price == null ? other.price == null : other.price != null && price.compareTo(other.price) == 0)
                && attributes.equals(other.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ref, section, name, description, attributes);
    }
}
