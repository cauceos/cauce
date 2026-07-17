package dev.cauce.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PageParamsTest {

    @Test
    void parse_noCursor_returnsLimitAndNullAfterId() {
        PageParams params = PageParams.parse(PageParams.DEFAULT_LIMIT, null);

        assertThat(params.limit()).isEqualTo(50);
        assertThat(params.afterId()).isNull();
        assertThat(params.fetchSize()).isEqualTo(51);
    }

    @Test
    void parse_limitAboveMax_clampsToMax() {
        PageParams params = PageParams.parse(999, null);

        assertThat(params.limit()).isEqualTo(PageParams.MAX_LIMIT);
        assertThat(params.fetchSize()).isEqualTo(PageParams.MAX_LIMIT + 1);
    }

    @Test
    void parse_limitZero_throwsIllegalArgument() {
        assertThatThrownBy(() -> PageParams.parse(0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
    }

    @Test
    void parse_negativeLimit_throwsIllegalArgument() {
        assertThatThrownBy(() -> PageParams.parse(-5, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
    }

    @Test
    void parse_validCursor_returnsParsedUuid() {
        UUID cursor = UUID.randomUUID();

        PageParams params = PageParams.parse(10, cursor.toString());

        assertThat(params.afterId()).isEqualTo(cursor);
    }

    @Test
    void parse_malformedCursor_throwsInvalidCursorWithoutEchoingIt() {
        assertThatThrownBy(() -> PageParams.parse(10, "not-a-uuid<script>"))
                .isInstanceOf(InvalidCursorException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("script"));
    }
}
