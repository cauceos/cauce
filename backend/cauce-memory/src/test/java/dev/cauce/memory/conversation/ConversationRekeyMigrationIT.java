package dev.cauce.memory.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The V26 re-key backfill and the V27 drop, driven by Flyway directly: migrate to V25, seed
 * conversations in the pre-identity shape, migrate on, and check what the migrations derived. No Spring context —
 * the subject is the SQL, and every test starts from an empty schema.
 */
@Testcontainers
class ConversationRekeyMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    private final UUID operator = UUID.randomUUID();
    private final UUID partner = UUID.randomUUID();
    private final UUID client = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();

    @BeforeEach
    void migrateToV25AndSeedTheHierarchy() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway(null).clean();
        flyway("25").migrate();

        jdbc.update("INSERT INTO tenants (id, parent_tenant_id, tier, name) VALUES (?, NULL, 'OPERATOR', 'O')",
                operator);
        jdbc.update("INSERT INTO tenants (id, parent_tenant_id, tier, name) VALUES (?, ?, 'PARTNER', 'P')",
                partner, operator);
        jdbc.update("INSERT INTO tenants (id, parent_tenant_id, tier, name) VALUES (?, ?, 'CLIENT', 'C')",
                client, partner);
        jdbc.update("INSERT INTO agents (id, tenant_id, name, system_prompt, model_provider, model_name, status) "
                + "VALUES (?, ?, 'A', 'p', 'ollama', 'm', 'ACTIVE')", agent, client);
    }

    @Test
    void backfill_derivesTheKindFromTheChannel_andOwnsTheIdentityByTheAgentsTenant() {
        UUID api = seedConversation("api", "user-1", "OPEN");
        UUID telegram = seedConversation("telegram", "987654321", "OPEN");
        seedChannelConfig("telegram");

        flyway("26").migrate();

        Map<String, Object> apiIdentity = identityOf(api);
        assertThat(apiIdentity).containsEntry("channel_type", "api").containsEntry("kind", "CLIENT_REFERENCE")
                .containsEntry("value", "user-1").containsEntry("tenant_id", client);
        Map<String, Object> telegramIdentity = identityOf(telegram);
        assertThat(telegramIdentity).containsEntry("channel_type", "telegram")
                .containsEntry("kind", "PROVIDER_USER_ID").containsEntry("value", "987654321");
        assertThat(((UUID) apiIdentity.get("id")).version()).isEqualTo(7);
    }

    @Test
    void backfill_givesOneIdentityToEveryThreadOfTheSameInterlocutor() {
        UUID closed = seedConversation("api", "user-1", "CLOSED");
        UUID open = seedConversation("api", "user-1", "OPEN");

        flyway("26").migrate();

        assertThat(identityOf(closed).get("id")).isEqualTo(identityOf(open).get("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identities", Integer.class)).isEqualTo(1);
    }

    @Test
    void backfill_stampsTheConfigOnlyWhenTheAgentHasExactlyOneForTheChannel() {
        UUID onlyBot = seedConversation("telegram", "111", "OPEN");
        UUID onlyConfig = seedChannelConfig("telegram");
        UUID api = seedConversation("api", "user-1", "OPEN");

        flyway("26").migrate();

        assertThat(configOf(onlyBot)).isEqualTo(onlyConfig);
        assertThat(configOf(api)).isNull();
    }

    @Test
    void backfill_leavesTheConfigNull_whenTheOriginIsAmbiguous() {
        UUID conversation = seedConversation("telegram", "111", "OPEN");
        seedChannelConfig("telegram");
        seedChannelConfig("telegram");

        flyway("26").migrate();

        assertThat(configOf(conversation)).isNull();
        assertThat(jdbc.queryForObject("SELECT identity_id FROM conversations WHERE id = ?", UUID.class,
                conversation)).isNotNull();
    }

    @Test
    void backfill_abortsOnAChannelWithoutAKindMapping_insteadOfGuessing() {
        seedConversation("whatsapp", "+34600111222", "OPEN");

        assertThatThrownBy(() -> flyway("26").migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("has no identity kind mapping");
    }

    @Test
    void openThreadIndex_isNullsNotDistinct_soTwoOpenApiThreadsForOneIdentityCannotCoexist() {
        flyway("26").migrate();
        UUID identity = UUID.randomUUID();
        jdbc.update("INSERT INTO identities (id, tenant_id, channel_type, kind, value) "
                + "VALUES (?, ?, 'api', 'CLIENT_REFERENCE', 'user-1')", identity, client);
        String insert = "INSERT INTO conversations (id, agent_id, identity_id, channel_config_id, channel_type, "
                + "external_identity_ref, status) VALUES (?, ?, ?, NULL, 'api', 'user-1', 'OPEN') "
                + "ON CONFLICT (agent_id, identity_id, channel_config_id) WHERE status = 'OPEN' DO NOTHING";

        int first = jdbc.update(insert, UUID.randomUUID(), agent, identity);
        int second = jdbc.update(insert, UUID.randomUUID(), agent, identity);

        assertThat(List.of(first, second)).containsExactly(1, 0);
    }

    @Test
    void v27_dropsTheOpaqueColumnsAndTheirIndex_keepingTheIdentityOfEveryThread() {
        UUID conversation = seedConversation("api", "user-1", "OPEN");

        flyway(null).migrate();

        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'conversations'", String.class))
                .contains("identity_id", "channel_config_id")
                .doesNotContain("channel_type", "external_identity_ref");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_conversations_identity'",
                Integer.class)).isZero();
        assertThat(identityOf(conversation)).containsEntry("value", "user-1");
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

    private UUID seedConversation(String channelType, String ref, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO conversations (id, agent_id, channel_type, external_identity_ref, status, closed_at) "
                        + "VALUES (?, ?, ?, ?, ?, CASE WHEN ? = 'CLOSED' THEN now() END)",
                id, agent, channelType, ref, status, status);
        return id;
    }

    private UUID seedChannelConfig(String channelType) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO channel_configs (id, tenant_id, agent_id, channel_type, credential, "
                + "webhook_secret_hash, status) VALUES (?, ?, ?, ?, 'token', 'hash', 'ACTIVE')",
                id, client, agent, channelType);
        return id;
    }

    private Map<String, Object> identityOf(UUID conversationId) {
        return jdbc.queryForMap("SELECT i.* FROM identities i JOIN conversations c ON c.identity_id = i.id "
                + "WHERE c.id = ?", conversationId);
    }

    private UUID configOf(UUID conversationId) {
        return jdbc.queryForObject("SELECT channel_config_id FROM conversations WHERE id = ?", UUID.class,
                conversationId);
    }
}
