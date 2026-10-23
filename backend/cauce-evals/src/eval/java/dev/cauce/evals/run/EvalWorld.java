package dev.cauce.evals.run;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.business.booking.BookingService;
import dev.cauce.business.owner.OwnerNotificationDispatcher;
import dev.cauce.business.owner.OwnerService;
import dev.cauce.channels.spi.ReplyMarkdown;
import dev.cauce.core.UuidGenerator;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.inbound.InboundMessageInterceptor.InboundText;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.evals.check.TurnTrace;
import dev.cauce.evals.check.TurnTrace.ObservedBooking;
import dev.cauce.evals.check.TurnTrace.ObservedCase;
import dev.cauce.evals.check.TurnTrace.ObservedNotice;
import dev.cauce.evals.check.TurnTrace.ObservedState;
import dev.cauce.evals.check.TurnTrace.ToolUse;
import dev.cauce.evals.scenario.RelativeDates;
import dev.cauce.evals.scenario.Scenario;
import dev.cauce.evals.scenario.Scenario.Setup;
import dev.cauce.orchestration.worker.PendingInvocationWorker;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.TenantService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Time;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * One repetition of one scenario in its own business (ADR 0009, E8): a fresh client tenant with the
 * fixture's sheet, menu and agenda, the pack's two agents, the owner linked unless the scenario says
 * not, and the scenario's {@code previo} seeded straight into the tables (a booking of 50 for a full
 * shift is what the scenario needs, not something the agenda would accept). Turns go through the
 * real messages endpoint and the worker; what they left is read from the records.
 */
final class EvalWorld {

    static final String PERSON = "cliente-eval";
    private static final String OTHER_PERSON = "otro-cliente-eval";

    private final Services s;
    private final Scenario scenario;
    private final UUID tenantId;
    private final String auth;
    private final UUID customerAgentId;
    private final ZoneId zone;
    private final LocalDate today;
    private final Map<String, String> codes = new HashMap<>();

    /** The application beans a world uses, gathered once per run. */
    record Services(MockMvc mockMvc, ObjectMapper json, TenantService tenants, ApiKeyService apiKeys,
                    ConversationService conversations, OwnerService owners, OwnerNotificationDispatcher dispatcher,
                    PendingInvocationWorker worker, BookingService bookings, DataSource adminDataSource, Clock clock,
                    Path packDir) {

        JdbcTemplate admin() {
            return new JdbcTemplate(adminDataSource);
        }
    }

    private EvalWorld(Services s, Scenario scenario, UUID tenantId, String auth, UUID customerAgentId) {
        this.s = s;
        this.scenario = scenario;
        this.tenantId = tenantId;
        this.auth = auth;
        this.customerAgentId = customerAgentId;
        this.zone = asTenant(tenantId, () -> s.bookings().zoneOf(tenantId));
        this.today = LocalDate.ofInstant(s.clock().instant(), zone);
    }

    /** A new business for {@code scenario} under {@code partnerId}, with its {@code previo} seeded. */
    static EvalWorld create(Services s, Scenario scenario, UUID partnerId, int repetition, String agentProvider,
                            String agentModel) throws Exception {
        UUID tenantId = asTenant(partnerId, () -> s.tenants().createClient(scenario.id() + " #" + repetition, partnerId)
                .id());
        String auth = "Bearer " + asTenant(tenantId, () -> s.apiKeys().createApiKey(tenantId, "eval").plaintextKey());
        Path fixture = s.packDir().resolve(scenario.fixture());
        putJson(s, auth, "/v1/tenants/" + tenantId + "/business-profile", read(fixture.resolve("ficha.json")));
        putJson(s, auth, "/v1/tenants/" + tenantId + "/catalog", read(fixture.resolve("carta.json")));
        putJson(s, auth, "/v1/tenants/" + tenantId + "/agenda", read(fixture.resolve("agenda.json")));
        JsonNode grants = s.json().readTree(read(s.packDir().resolve("herramientas.json")));
        JsonNode fallbacks = s.json().readTree(read(s.packDir().resolve("respaldo.json")));
        UUID customer = createAgent(s, auth, tenantId, "Camarero", read(s.packDir().resolve("agente-clientes.prompt.md")),
                names(grants.get("agente_clientes")), fallbacks.get("agente_clientes").asText(), agentProvider, agentModel);
        UUID owner = createAgent(s, auth, tenantId, "Canal del dueño", read(s.packDir().resolve("agente-dueno.prompt.md")),
                names(grants.get("agente_dueno")), fallbacks.get("agente_dueno").asText(), agentProvider, agentModel);
        EvalWorld world = new EvalWorld(s, scenario, tenantId, auth, customer);
        boolean ownerLinked = scenario.setup().stream().map(Setup::ownerLinked)
                .filter(java.util.Objects::nonNull).findFirst().orElse(true);
        if (ownerLinked) {
            world.linkOwner(owner);
        }
        world.seed();
        return world;
    }

    /** The codes the {@code previo} bookings named with {@code como} got. */
    Map<String, String> codes() {
        return Map.copyOf(codes);
    }

    LocalDate resolveDate(String spec) {
        return RelativeDates.resolve(spec, today);
    }

    /**
     * Sends {@code message} as the scenario's person, runs the worker until the invocation ends,
     * dispatches the owner's notices, and returns what the turn left behind.
     */
    /** What a turn left behind, and the invocation that answered it. */
    /** A played turn; {@code executionFailure} is the cause when its invocation did not complete. */
    record Played(TurnTrace trace, String invocationId, Optional<String> executionFailure) {
    }

    Played turn(String message) throws Exception {
        String body = s.mockMvc().perform(post("/v1/agents/" + customerAgentId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                        .content(s.json().writeValueAsString(Map.of("external_identity_ref", PERSON, "content", message))))
                .andReturn().getResponse().getContentAsString();
        JsonNode accepted = s.json().readTree(body);
        UUID conversationId = UUID.fromString(accepted.get("conversation_id").asText());
        UUID messageId = UUID.fromString(accepted.get("message_id").asText());
        String invocationId = accepted.get("invocation_id").asText();
        s.worker().pollAndProcess();
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofMillis(500)).until(() -> {
            String status = s.json().readTree(s.mockMvc().perform(get("/v1/invocations/" + invocationId)
                    .header(HttpHeaders.AUTHORIZATION, auth)).andReturn().getResponse().getContentAsString())
                    .path("status").asText();
            return status.equals("COMPLETED") || status.equals("FAILED") || status.equals("ABANDONED");
        });
        s.dispatcher().dispatchDue();
        return new Played(trace(message, conversationId, messageId), invocationId, executionFailure(invocationId));
    }

    /** Why the invocation did not complete (status, failure type and the internal error), if it did not. */
    private Optional<String> executionFailure(String invocationId) {
        Map<String, Object> row = s.admin().queryForMap("SELECT status, failure_type, last_error FROM "
                + "pending_invocations WHERE id = ?::uuid", invocationId);
        if ("COMPLETED".equals(row.get("status"))) {
            return Optional.empty();
        }
        String error = row.get("last_error") == null ? "sin detalle" : (String) row.get("last_error");
        return Optional.of(row.get("status") + " · " + row.get("failure_type") + " · "
                + (error.length() > 300 ? error.substring(0, 300) + "…" : error));
    }

    /** Runs the nightly summary for today, {@code times} times, and returns what it left behind. */
    TurnTrace dailyDigest(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            s.mockMvc().perform(post("/v1/tenants/" + tenantId + "/daily-digests").header(HttpHeaders.AUTHORIZATION, auth));
        }
        s.dispatcher().dispatchDue();
        return new TurnTrace(Scenario.DAILY_DIGEST, List.of(), "", "", latestNotice(), state());
    }

    /** The tool names the audit chain recorded for {@code invocationId}, to cross-check the trace (E4). */
    List<String> auditedTools(String invocationId) {
        return s.admin().queryForList("SELECT payload->>'tool_name' FROM audit_outbox WHERE event_type = "
                + "'conduct.tool.called' AND payload->>'invocation_id' = ? ORDER BY created_at, id", String.class,
                invocationId);
    }

    private TurnTrace trace(String message, UUID conversationId, UUID messageId) throws Exception {
        List<Map<String, Object>> rows = s.admin().queryForList("SELECT role, content, tool_content::text AS tool "
                + "FROM messages WHERE conversation_id = ? AND role <> 'USER' "
                + "AND seq > (SELECT seq FROM messages WHERE id = ?) ORDER BY seq", conversationId, messageId);
        List<ToolUse> tools = new ArrayList<>();
        Map<String, Integer> callIndex = new HashMap<>();
        String reply = "";
        for (Map<String, Object> row : rows) {
            String role = (String) row.get("role");
            JsonNode tool = row.get("tool") == null ? null : s.json().readTree((String) row.get("tool"));
            switch (role) {
                case "TOOL_CALL" -> {
                    callIndex.put(tool.path("tool_call_id").asText(), tools.size());
                    tools.add(new ToolUse(tool.path("tool_name").asText(),
                            s.json().convertValue(tool.path("input"), new TypeReference<Map<String, Object>>() { }), ""));
                }
                case "TOOL_RESULT" -> {
                    Integer at = callIndex.get(tool.path("tool_call_id").asText());
                    if (at != null) {
                        ToolUse call = tools.get(at);
                        tools.set(at, new ToolUse(call.name(), call.input(), (String) row.get("content")));
                    }
                }
                case "AGENT", "FALLBACK" -> reply = (String) row.get("content");
                default -> {
                    // SYSTEM: the orchestrator's own record, not part of the turn.
                }
            }
        }
        String delivered = "telegram".equals(scenario.channel()) ? ReplyMarkdown.toHtml(reply) : reply;
        return new TurnTrace(message, tools, reply, delivered, latestNotice(), state());
    }

    private String latestNotice() {
        List<String> latest = s.admin().queryForList("SELECT content FROM owner_notifications WHERE tenant_id = ? "
                + "ORDER BY created_at DESC, id DESC LIMIT 1", String.class, tenantId);
        return latest.isEmpty() ? null : latest.get(0);
    }

    private ObservedState state() {
        JdbcTemplate admin = s.admin();
        List<ObservedBooking> bookings = admin.query("SELECT CASE WHEN status = 'HELD' AND hold_expires_at <= now() "
                        + "THEN 'HELD_EXPIRED' ELSE status END AS status, service_date, shift_id, "
                        + "to_char(requested_time, 'HH24:MI') AS time, party_size, code, contact_name FROM bookings "
                        + "WHERE tenant_id = ? ORDER BY id",
                (rs, i) -> new ObservedBooking(rs.getString("status"), rs.getDate("service_date").toLocalDate(),
                        rs.getString("shift_id"), rs.getString("time"), rs.getInt("party_size"), rs.getString("code"),
                        rs.getString("contact_name")), tenantId);
        List<ObservedCase> cases = admin.query("SELECT category, status FROM handoffs WHERE tenant_id = ? ORDER BY id",
                (rs, i) -> new ObservedCase(rs.getString("category"), rs.getString("status")), tenantId);
        Integer questions = admin.queryForObject("SELECT count(*) FROM knowledge_gaps WHERE tenant_id = ?",
                Integer.class, tenantId);
        List<ObservedNotice> notices = admin.query("SELECT kind, status, reason, content FROM owner_notifications "
                        + "WHERE tenant_id = ? ORDER BY created_at, id",
                (rs, i) -> new ObservedNotice(rs.getString("kind"), rs.getString("status"), rs.getString("reason"),
                        rs.getString("content")), tenantId);
        List<String> digests = admin.queryForList("SELECT content FROM daily_digests WHERE tenant_id = ? "
                + "ORDER BY created_at, id", String.class, tenantId);
        return new ObservedState(bookings, cases, questions == null ? 0 : questions, notices, digests.size(),
                digests.isEmpty() ? null : digests.get(digests.size() - 1));
    }

    private void linkOwner(UUID ownerAgentId) throws Exception {
        String binding = s.mockMvc().perform(post("/v1/agents/" + ownerAgentId + "/channels")
                        .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                        .content(s.json().writeValueAsString(Map.of("channel_type", "telegram",
                                "credential", "eval-owner-bot"))))
                .andReturn().getResponse().getContentAsString();
        UUID configId = UUID.fromString(s.json().readTree(binding).get("id").asText());
        String issued = s.mockMvc().perform(post("/v1/tenants/" + tenantId + "/owner-link-codes")
                        .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                        .content(s.json().writeValueAsString(Map.of("channel_config_id", configId))))
                .andReturn().getResponse().getContentAsString();
        String code = s.json().readTree(issued).get("code").asText();
        asTenant(tenantId, () -> s.owners().intercept(new InboundText(tenantId, configId, "telegram",
                IdentityKind.PROVIDER_USER_ID, "eval-owner", code)));
    }

    /** Seeds the {@code previo}: bookings, open cases and noted questions, straight into the tables. */
    private void seed() {
        if (scenario.setup().isEmpty()) {
            return;
        }
        JdbcTemplate admin = s.admin();
        Conversation person = conversationOf(PERSON);
        Conversation other = conversationOf(OTHER_PERSON);
        for (Setup setup : scenario.setup()) {
            if (setup.booking() != null) {
                Scenario.SetupBooking b = setup.booking();
                Conversation owner = Boolean.FALSE.equals(b.sameCustomer()) ? other : person;
                String code = Booking.newCode();
                // With a phone, as a booking made through the agent has: an agenda that requires one
                // refuses to change a booking without it.
                admin.update("INSERT INTO bookings (id, tenant_id, code, agent_id, conversation_id, identity_id, "
                                + "service_date, shift_id, requested_time, party_size, contact_name, contact_phone, status, "
                                + "proposed_in_message_id, hold_expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                                + "now() + interval '10 minutes')",
                        UuidGenerator.newV7(), tenantId, code, customerAgentId, owner.id(), owner.identityId(),
                        Date.valueOf(resolveDate(b.date())), b.shift(), b.time() == null ? null
                                : Time.valueOf(LocalTime.parse(b.time())), b.partySize(),
                        b.contactName() == null ? "Ana" : b.contactName(), "600 123 456", b.status(), UUID.randomUUID());
                if (b.alias() != null) {
                    codes.put(b.alias(), code);
                }
            }
            if (setup.handoff() != null) {
                admin.update("INSERT INTO handoffs (id, tenant_id, code, agent_id, conversation_id, identity_id, category, "
                                + "summary, contact_name, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        UuidGenerator.newV7(), tenantId, Booking.newCode(), customerAgentId, other.id(),
                        other.identityId(), setup.handoff().category(),
                        setup.handoff().summary() == null ? "Caso de prueba" : setup.handoff().summary(),
                        setup.handoff().contactName() == null ? "Marta" : setup.handoff().contactName(),
                        setup.handoff().status());
            }
            if (setup.question() != null) {
                admin.update("INSERT INTO knowledge_gaps (id, tenant_id, agent_id, conversation_id, question) "
                        + "VALUES (?, ?, ?, ?, ?)", UuidGenerator.newV7(), tenantId, customerAgentId, other.id(),
                        setup.question());
            }
        }
    }

    private record Conversation(UUID id, UUID identityId) {
    }

    private Conversation conversationOf(String person) {
        var conversation = asTenant(tenantId, () -> s.conversations().startConversation(customerAgentId, "api",
                IdentityKind.CLIENT_REFERENCE, person, null));
        return new Conversation(conversation.id(), conversation.identityId());
    }

    private static UUID createAgent(Services s, String auth, UUID tenantId, String name, String prompt,
                                    List<String> tools, String fallback, String provider, String model) throws Exception {
        String body = s.mockMvc().perform(post("/v1/tenants/" + tenantId + "/agents").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(s.json().writeValueAsString(Map.of("name", name, "system_prompt", prompt,
                                "model_provider", provider, "model_name", model, "tools", tools,
                                "fallback_reply", fallback))))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = s.json().readTree(body);
        if (!created.has("id")) {
            throw new IllegalStateException("Could not create the agent " + name + ": " + body);
        }
        return UUID.fromString(created.get("id").asText());
    }

    private static void putJson(Services s, String auth, String path, String json) throws Exception {
        int status = s.mockMvc().perform(put(path).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse().getStatus();
        if (status != 200) {
            throw new IllegalStateException("PUT " + path + " returned " + status);
        }
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(n -> names.add(n.asText()));
        return names;
    }

    static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    static <T> T asTenant(UUID tenantId, Supplier<T> action) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    UUID tenantId() {
        return tenantId;
    }
}
