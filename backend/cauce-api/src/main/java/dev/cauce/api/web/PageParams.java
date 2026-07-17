package dev.cauce.api.web;

import java.util.UUID;

/**
 * Parsed pagination parameters of a list request. The cursor is opaque to clients (today the
 * UUIDv7 id of the last item of the previous page); a forged cursor is harmless — every keyset
 * query is scoped by its parent filter plus RLS, so an out-of-scope value only shifts the
 * window and can never leak rows.
 */
public record PageParams(int limit, UUID afterId) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    /**
     * Validates and normalizes the raw query parameters: a limit above {@link #MAX_LIMIT} is
     * clamped; a limit below 1 is rejected; an absent cursor means the first page.
     *
     * @throws IllegalArgumentException if {@code limit < 1} (surfaces as 400 {@code bad_request})
     * @throws InvalidCursorException if {@code cursor} is present but not parseable
     */
    public static PageParams parse(int limit, String cursor) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        return new PageParams(Math.min(limit, MAX_LIMIT), parseCursor(cursor));
    }

    /** One row beyond the page, so the query result reveals whether a next page exists. */
    public int fetchSize() {
        return limit + 1;
    }

    private static UUID parseCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Invalid cursor");
        }
    }
}
