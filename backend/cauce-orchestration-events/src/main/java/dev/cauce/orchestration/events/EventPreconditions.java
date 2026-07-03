package dev.cauce.orchestration.events;

/** Shared payload validations for the event records' compact constructors. */
final class EventPreconditions {

    private EventPreconditions() {
    }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative but was " + value);
        }
    }
}
