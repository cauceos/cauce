package dev.cauce.api.web;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Uniform envelope for paginated list endpoints: {@code {"data": [...], "next_cursor": ...}}.
 * {@code nextCursor} is the opaque cursor for the next page, or explicitly {@code null} on the
 * last page — the null is the terminator, so this record must never be serialized with a
 * non-null-only inclusion.
 */
public record PageResponse<T>(List<T> data, String nextCursor) {

    /**
     * Builds a page from a {@code limit + 1}-sized fetch: the extra row only signals that a
     * next page exists and is trimmed off; the cursor is the id of the last returned item.
     */
    public static <T> PageResponse<T> of(List<T> fetched, int limit, Function<T, UUID> idOf) {
        if (fetched.size() <= limit) {
            return new PageResponse<>(fetched, null);
        }
        List<T> page = fetched.subList(0, limit);
        return new PageResponse<>(page, idOf.apply(page.get(limit - 1)).toString());
    }
}
