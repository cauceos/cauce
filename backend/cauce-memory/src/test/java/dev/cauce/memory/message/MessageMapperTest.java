package dev.cauce.memory.message;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageMapperTest {

    private final MessageMapper mapper = new MessageMapper();

    @Test
    void roundTrip_domainToEntityToDomain_preservesAllFields() {
        Message original = Message.from(UUID.randomUUID(), MessageRole.USER,
                "  Hello, with whitespace preserved\n");

        Message roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped.id()).isEqualTo(original.id());
        assertThat(roundTripped.conversationId()).isEqualTo(original.conversationId());
        assertThat(roundTripped.role()).isEqualTo(MessageRole.USER);
        assertThat(roundTripped.content()).isEqualTo(original.content());
        assertThat(roundTripped.createdAt()).isEqualTo(original.createdAt());
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toEntity_copiesAllFields() {
        Message message = Message.from(UUID.randomUUID(), MessageRole.AGENT, "Reply");

        MessageEntity entity = mapper.toEntity(message);

        assertThat(entity.getId()).isEqualTo(message.id());
        assertThat(entity.getConversationId()).isEqualTo(message.conversationId());
        assertThat(entity.getRole()).isEqualTo(MessageRole.AGENT);
        assertThat(entity.getContent()).isEqualTo("Reply");
        assertThat(entity.getCreatedAt()).isEqualTo(message.createdAt());
    }
}
