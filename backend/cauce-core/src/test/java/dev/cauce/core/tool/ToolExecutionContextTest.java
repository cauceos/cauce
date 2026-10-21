package dev.cauce.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolExecutionContextTest {

    @Test
    void constructor_requiresEveryId() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new ToolExecutionContext(null, id, id, id, id, id))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ToolExecutionContext(id, id, id, null, id, id))
                .isInstanceOf(NullPointerException.class);
        assertThat(new ToolExecutionContext(id, id, id, id, id, id).identityId()).isEqualTo(id);
    }
}
