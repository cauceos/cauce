package dev.cauce.llm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LlmMessageTest {

    @Test
    void factories_setRoleAndContent() {
        assertThat(LlmMessage.user("hi").role()).isEqualTo(LlmRole.USER);
        assertThat(LlmMessage.assistant("hi").role()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(LlmMessage.system("hi").role()).isEqualTo(LlmRole.SYSTEM);
        assertThat(LlmMessage.user("hello").content()).isEqualTo("hello");
    }

    @Test
    void rejectsNullRole() {
        assertThatThrownBy(() -> new LlmMessage(null, "hi"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullContent() {
        assertThatThrownBy(() -> LlmMessage.user(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void valueEquality() {
        assertThat(LlmMessage.user("hi")).isEqualTo(LlmMessage.user("hi"));
        assertThat(LlmMessage.user("hi")).isNotEqualTo(LlmMessage.assistant("hi"));
    }
}
