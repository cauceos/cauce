package dev.cauce.memory.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The V54 backfill of message parts, driven by Flyway directly: migrate to V53, seed messages in
 * the pre-parts shape (text in {@code content}, tool payload in {@code tool_content}), migrate on,
 * and check that every message reads back as it was. No Spring context — the subject is the SQL.
 */
@Testcontainers
class MessagePartsMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private final MessageMapper mapper = new MessageMapper();

    private final UUID conversation = UUID.randomUUID();

    @BeforeEach
    void migrateToV53AndSeedAConversation() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway(null).clean();
        flyway("53").migrate();

        UUID operator = UUID.randomUUID();
        UUID partner = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID identity = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, parent_tenant_id, tier, name) VALUES (?, NULL, 'OPERATOR', 'O')", operator);
        jdbc.update("INSERT INTO tenants (id, parent_tenant_id, tier, name) VALUES (?, ?, 'PARTNER', 'P')",
                partner, operator);
        jdbc.update("INSERT INTO tenants (id, parent_tenant_id, tier, name) VALUES (?, ?, 'CLIENT', 'C')",
                client, partner);
        jdbc.update("INSERT INTO agents (id, tenant_id, name, system_prompt, model_provider, model_name, status) "
                + "VALUES (?, ?, 'A', 'p', 'ollama', 'm', 'ACTIVE')", agent, client);
        jdbc.update("INSERT INTO identities (id, tenant_id, channel_type, kind, value) "
                + "VALUES (?, ?, 'api', 'CLIENT_REFERENCE', 'user-1')", identity, client);
        jdbc.update("INSERT INTO conversations (id, agent_id, identity_id, status) VALUES (?, ?, ?, 'OPEN')",
                conversation, agent, identity);
    }

    @Test
    void backfill_turnsEachKindOfMessageIntoItsParts_andTheyReadBackAsTheyWere() {
        UUID user = seed("USER", "Hola, ¿tenéis mesa?", null, null);
        UUID call = seed("TOOL_CALL", "check_availability",
                "{\"tool_call_id\": \"call-1\", \"tool_name\": \"check_availability\", \"input\": {\"party\": 4}}", null);
        UUID result = seed("TOOL_RESULT", "[empty result]",
                "{\"tool_call_id\": \"call-1\", \"tool_name\": \"check_availability\", \"output\": \"\", "
                        + "\"is_error\": false}", null);
        UUID fallback = seed("FALLBACK", "Perdona, inténtalo luego.", null, "LLM_ERROR");

        flyway(null).migrate();

        assertThat(read(user).content()).isEqualTo("Hola, ¿tenéis mesa?");
        assertThat(read(user).toolContent()).isEmpty();
        assertThat(read(call).toolContent()).contains(new ToolCall("call-1", "check_availability", Map.of("party", 4)));
        assertThat(read(call).content()).isEqualTo("check_availability");
        assertThat(read(result).toolContent()).contains(ToolResult.success("call-1", "check_availability", ""));
        assertThat(read(result).content()).as("the rendering, derived again").isEqualTo("[empty result]");
        assertThat(read(fallback).content()).isEqualTo("Perdona, inténtalo luego.");
        assertThat(read(fallback).failureType()).contains("LLM_ERROR");
    }

    @Test
    void afterTheBackfill_partsIsTheOnlyContent_andMustBeANonEmptyArray() {
        seed("USER", "Hola", null, null);

        flyway(null).migrate();

        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'messages'", String.class))
                .contains("parts").doesNotContain("content", "tool_content");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO messages (id, conversation_id, role, parts, created_at) "
                + "VALUES (?, ?, 'USER', '[]'::jsonb, now())", UUID.randomUUID(), conversation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID seed(String role, String content, String toolContent, String failureType) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO messages (id, conversation_id, role, content, tool_content, failure_type, created_at) "
                + "VALUES (?, ?, ?, ?, ?::jsonb, ?, now())", id, conversation, role, content, toolContent, failureType);
        return id;
    }

    /** The message as the application reads it: the row's parts through the mapper. */
    private Message read(UUID id) {
        Map<String, Object> row = jdbc.queryForMap("SELECT role, parts::text AS parts, failure_type, created_at "
                + "FROM messages WHERE id = ?", id);
        List<Map<String, Object>> parts = Json.parse((String) row.get("parts"));
        return mapper.toDomain(new MessageEntity(id, conversation, MessageRole.valueOf((String) row.get("role")),
                parts, (String) row.get("failure_type"), ((java.sql.Timestamp) row.get("created_at")).toInstant()));
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    /** Reads the jsonb text as Hibernate would: plain maps, lists, strings, numbers and booleans. */
    private static final class Json {

        static List<Map<String, Object>> parse(String text) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
