package dev.cauce.core.business.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeGapTest {

    @Test
    void record_stripsTheQuestion_andRejectsBlankOrOverlongOnes() {
        UUID id = UUID.randomUUID();

        assertThat(KnowledgeGap.record(id, id, id, "  ¿Tenéis wifi? ").question()).isEqualTo("¿Tenéis wifi?");
        assertThatThrownBy(() -> KnowledgeGap.record(id, id, id, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeGap.record(id, id, id, "x".repeat(KnowledgeGap.MAX_QUESTION_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
