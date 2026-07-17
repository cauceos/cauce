package dev.cauce.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.UuidGenerator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    private record Item(UUID id) {
    }

    private static List<Item> items(int n) {
        return java.util.stream.Stream.generate(() -> new Item(UuidGenerator.newV7()))
                .limit(n).toList();
    }

    @Test
    void of_moreRowsThanLimit_trimsAndSetsNextCursorToLastReturnedId() {
        List<Item> fetched = items(3); // a limit-2 request fetched limit + 1

        PageResponse<Item> page = PageResponse.of(fetched, 2, Item::id);

        assertThat(page.data()).containsExactly(fetched.get(0), fetched.get(1));
        assertThat(page.nextCursor()).isEqualTo(fetched.get(1).id().toString());
    }

    @Test
    void of_exactlyLimitRows_returnsAllWithNullNextCursor() {
        List<Item> fetched = items(2);

        PageResponse<Item> page = PageResponse.of(fetched, 2, Item::id);

        assertThat(page.data()).isEqualTo(fetched);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void of_emptyFetch_returnsEmptyDataWithNullCursor() {
        PageResponse<Item> page = PageResponse.of(List.of(), 2, Item::id);

        assertThat(page.data()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }
}
