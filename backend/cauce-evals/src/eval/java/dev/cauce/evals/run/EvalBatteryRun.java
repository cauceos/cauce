package dev.cauce.evals.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.CauceApplication;
import dev.cauce.business.booking.BookingService;
import dev.cauce.business.owner.OwnerNotificationDispatcher;
import dev.cauce.business.owner.OwnerService;
import dev.cauce.evals.check.CheckResult;
import dev.cauce.evals.check.CheckResult.Outcome;
import dev.cauce.evals.check.Checker;
import dev.cauce.evals.check.GeneralRules;
import dev.cauce.evals.check.Menu;
import dev.cauce.evals.check.TurnTrace;
import dev.cauce.evals.cost.CostEstimate;
import dev.cauce.evals.cost.PriceTable;
import dev.cauce.evals.judge.Calibration;
import dev.cauce.evals.judge.Judge;
import dev.cauce.evals.judge.LlmJudge;
import dev.cauce.evals.report.Aggregation;
import dev.cauce.evals.report.Report;
import dev.cauce.evals.report.RunSummary;
import dev.cauce.evals.report.ScenarioRun;
import dev.cauce.evals.scenario.Scenario;
import dev.cauce.evals.scenario.Scenario.Kind;
import dev.cauce.evals.scenario.ScenarioLoader;
import dev.cauce.llm.spi.LlmProvider;
import dev.cauce.llm.spi.LlmProviderRegistry;
import dev.cauce.llm.spi.SystemDefaultLlmCredential;
import dev.cauce.orchestration.worker.PendingInvocationWorker;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.TenantService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The eval run (ADR 0009): loads the pack's scenarios, estimates the cost and stops for
 * confirmation over the limit, calibrates the judge, runs every selected scenario N times, each in
 * a business of its own, against the real model, and writes the report and the history summary.
 * It fails when a blocking scenario or a general rule is under 100 %.
 *
 * <p>Started only by {@code ./gradlew :cauce-evals:eval}: it is not a test of the build.
 */
@SpringBootTest(classes = CauceApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EvalInfrastructure.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "cauce.orchestration.worker.enabled=true",
        "cauce.orchestration.worker.poll-interval-ms=3600000",
        "cauce.orchestration.worker.reaper.enabled=false",
        "cauce.governance.audit.drainer.enabled=false",
        "cauce.business.notifications.enabled=false",
        "cauce.business.digest.enabled=false",
        "logging.level.dev.cauce=WARN"
})
class EvalBatteryRun {

    private static final EvalSettings SETTINGS = EvalSettings.fromSystemProperties();
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
                    .withInitScript("testcontainers/cauce-app-role.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "cauce_app");
        registry.add("spring.datasource.password", () -> "cauce_app_test");
        registry.add("cauce.admin.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("cauce.admin.datasource.username", POSTGRES::getUsername);
        registry.add("cauce.admin.datasource.password", POSTGRES::getPassword);
        // A rehearsal puts its own model under the agent's provider id: the real one must not register.
        if (SETTINGS.rehearsal()) {
            registry.add("cauce.llm." + SETTINGS.agentProvider() + ".enabled", () -> "false");
        }
        // A judge from an OpenAI-compatible provider has to be enabled to be registered.
        SETTINGS.judgeProvider().filter(p -> !p.equals("anthropic")).ifPresent(p ->
                registry.add("cauce.llm.openai-compatible.providers." + p + ".enabled", () -> "true"));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private TenantService tenants;
    @Autowired private ApiKeyService apiKeys;
    @Autowired private ConversationService conversations;
    @Autowired private OwnerService owners;
    @Autowired private OwnerNotificationDispatcher dispatcher;
    @Autowired private PendingInvocationWorker worker;
    @Autowired private BookingService bookings;
    @Autowired private LlmProviderRegistry providers;
    @Autowired private Environment environment;
    @Autowired private Clock clock;
    @Autowired @Qualifier("adminDataSource") private DataSource adminDataSource;

    @Test
    void run() throws Exception {
        List<Scenario> selected = select(new ScenarioLoader().loadAll(SETTINGS.evalsDir()));
        assertThat(selected).as("no scenario matches " + SETTINGS.selection()).isNotEmpty();
        List<Scenario> conversationsToRun = selected.stream().filter(s -> s.kind() == Kind.CONVERSATION).toList();
        List<Scenario> digests = selected.stream().filter(s -> s.kind() == Kind.DIGEST).toList();
        List<Scenario> covered = selected.stream().filter(s -> s.kind() == Kind.COVERED_BY_IT).toList();
        int n = SETTINGS.repetitions();
        int modelTurns = conversationsToRun.stream().mapToInt(s -> s.turns().size()).sum() * n;
        long rubrics = conversationsToRun.stream().flatMap(s -> s.turns().stream())
                .filter(t -> t.expectation() != null && t.expectation().rubric() != null).count() * n;

        assertThat(providers.getProvider(SETTINGS.agentProvider()))
                .as("the agent's provider '" + SETTINGS.agentProvider() + "' is not available: set its API key")
                .isPresent();
        Optional<LlmJudge> judge = judge();
        List<Calibration.LabelledCase> calibrationCases = Calibration.load(SETTINGS.evalsDir().resolve("calibracion"));
        long labelled = calibrationCases.stream().filter(Calibration.LabelledCase::labelled).count();

        PriceTable prices = PriceTable.load(SETTINGS.evalsDir().resolve("precios.yaml"));
        Optional<PriceTable.Price> agentPrice = prices.priceOf(SETTINGS.agentProvider(), SETTINGS.agentModel());
        Optional<PriceTable.Price> judgePrice = judge.flatMap(j -> prices.priceOf(j.providerId(), j.model()));
        Optional<RunSummary> previous = Report.previous(SETTINGS.evalsDir().resolve("historial"));
        Long inPerTurn = previous.filter(p -> p.tokens() != null && p.tokens().modelTurns() > 0)
                .map(p -> p.tokens().input() / p.tokens().modelTurns()).orElse(null);
        Long outPerTurn = previous.filter(p -> p.tokens() != null && p.tokens().modelTurns() > 0)
                .map(p -> p.tokens().output() / p.tokens().modelTurns()).orElse(null);
        CostEstimate estimate = CostEstimate.of(modelTurns, (int) (judge.isPresent() ? rubrics + labelled * 3 : 0),
                inPerTurn, outPerTurn, agentPrice, judgePrice);
        System.out.printf("%nEvals: %d escenarios (%d conversaciones × %d, %d resúmenes, %d cubiertos por IT); "
                        + "%d turnos de modelo. Estimación: %s US$ (%s; límite %.2f US$)%n",
                selected.size(), conversationsToRun.size(), n, digests.size(), covered.size(), modelTurns,
                estimate.usd().map(c -> String.format("%.2f", c)).orElse("sin precio"), estimate.basis(),
                SETTINGS.limitUsd());
        assertThat(!estimate.exceeds(SETTINGS.limitUsd()) || SETTINGS.confirmed() || SETTINGS.rehearsal())
                .as("La estimación supera el límite de %.2f US$: revisa la selección o confirma con -Pevals.confirmar=true",
                        SETTINGS.limitUsd())
                .isTrue();

        // Calibrate the judge before it judges anything (E6).
        Calibration.Result calibration = judge.map(j -> Calibration.calibrate(j, calibrationCases))
                .orElse(new Calibration.Result(Calibration.Status.UNCALIBRATED, 0, List.of()));
        Optional<Judge> usable = calibration.accepted() ? judge.map(j -> j) : Optional.empty();
        String calibrationText = judge.isEmpty() ? "pendiente de juez: ninguno configurado (evals.juez.modelo)"
                : switch (calibration.status()) {
                    case ACCEPTED -> "calibrado con " + calibration.labelledCases() + " casos";
                    case UNCALIBRATED -> "sin calibrar: ningún caso etiquetado en calibracion/";
                    case REJECTED -> "rechazado: no coincide en " + calibration.disagreements();
                };
        System.out.println("Juez: " + judge.map(LlmJudge::describe).orElse("ninguno") + " · " + calibrationText);

        // Run.
        JdbcTemplate admin = new JdbcTemplate(adminDataSource);
        truncate(admin);
        UUID operatorId = tenants.bootstrapOperator("Operator").id();
        UUID partnerId = EvalWorld.asTenant(operatorId, () -> tenants.createPartner("Evals", operatorId).id());
        EvalWorld.Services services = new EvalWorld.Services(mockMvc, json, tenants, apiKeys, conversations, owners,
                dispatcher, worker, bookings, adminDataSource, clock, SETTINGS.packDir());
        Menu menu = Menu.load(SETTINGS.packDir().resolve("bar-ejemplo").resolve("carta.json"));
        List<ScenarioRun> runs = new ArrayList<>();
        boolean complete = true;
        run:
        for (Scenario scenario : conversationsToRun) {
            for (int rep = 1; rep <= n; rep++) {
                runs.add(conversation(services, scenario, partnerId, rep, menu, usable));
                if (spent(admin, judge, agentPrice, judgePrice) >= SETTINGS.limitUsd() && !SETTINGS.confirmed()) {
                    complete = false;
                    break run;
                }
            }
        }
        for (Scenario scenario : digests) {
            runs.add(digest(services, scenario, partnerId));
        }

        // Report.
        Map<String, Object> usage = admin.queryForMap("SELECT coalesce(sum(input_tokens), 0) AS i, "
                + "coalesce(sum(cache_read_tokens), 0) AS r, coalesce(sum(cache_write_tokens), 0) AS w, "
                + "coalesce(sum(output_tokens), 0) AS o, count(DISTINCT invocation_id) AS t FROM llm_usage_records");
        RunSummary.Tokens tokens = new RunSummary.Tokens(num(usage, "i"), num(usage, "r"), num(usage, "w"),
                num(usage, "o"), (int) num(usage, "t"), judge.map(LlmJudge::inputTokens).orElse(0L),
                judge.map(LlmJudge::outputTokens).orElse(0L));
        Double cost = agentPrice.map(p -> p.cost(tokens.input(), tokens.cacheRead(), tokens.cacheWrite(), tokens.output())
                + judgePrice.map(j -> j.cost(tokens.judgeInput(), 0, 0, tokens.judgeOutput())).orElse(0.0)).orElse(null);
        String now = clock.instant().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'"));
        String[] git = git();
        RunSummary summary = Aggregation.summarize(runs, covered, SETTINGS.warningThreshold(), new Aggregation.Meta(
                now, git[0], Boolean.parseBoolean(git[1]), SETTINGS.agentProvider() + "/" + SETTINGS.agentModel(),
                judge.map(LlmJudge::describe).orElse("ninguno"), calibrationText, footprint(), n, SETTINGS.selection(),
                complete, tokens, cost, prices.version()));
        writeOutputs(summary, previous, runs, now);
        if (SETTINGS.rehearsal()) {
            return; // a rehearsal's verdict says nothing about the agent
        }
        assertThat(Aggregation.blocks(summary)).as("bloqueantes por debajo del 100 %%: %s", summary.blockingFailures())
                .isFalse();
    }

    private ScenarioRun conversation(EvalWorld.Services services, Scenario scenario, UUID partnerId, int rep, Menu menu,
                                     Optional<Judge> judge) throws Exception {
        EvalWorld world = EvalWorld.create(services, scenario, partnerId, rep, SETTINGS.agentProvider(),
                SETTINGS.agentModel());
        Checker checker = new Checker(world.codes(), world::resolveDate);
        List<CheckResult> results = new ArrayList<>();
        StringBuilder transcript = new StringBuilder();
        for (int i = 0; i < scenario.turns().size(); i++) {
            Scenario.Turn turn = scenario.turns().get(i);
            String prefix = "turno " + (i + 1);
            EvalWorld.Played played = world.turn(turn.message());
            TurnTrace trace = played.trace();
            transcript.append("Cliente: ").append(turn.message()).append('\n');
            trace.tools().forEach(t -> transcript.append("  [").append(t.name()).append("] ").append(t.input()).append('\n'));
            transcript.append("Agente: ").append(trace.reply()).append("\n\n");
            List<String> audited = world.auditedTools(played.invocationId());
            List<String> traced = trace.tools().stream().map(TurnTrace.ToolUse::name).toList();
            if (!sameTools(audited, traced)) {
                results.add(new CheckResult(prefix + " · traza contra auditoría", Outcome.HARNESS_ERROR,
                        "mensajes " + traced + " / auditoría " + audited));
            }
            if (turn.expectation() != null) {
                results.addAll(checker.check(prefix, turn.expectation(), trace));
                if (turn.expectation().rubric() != null) {
                    results.add(rubric(prefix, turn, trace, judge));
                }
            }
            results.add(prefixed(prefix, GeneralRules.g1(trace, menu)));
            results.add(prefixed(prefix, GeneralRules.grounded(trace, menu)));
        }
        return new ScenarioRun(scenario, rep, results, transcript.toString().strip());
    }

    private ScenarioRun digest(EvalWorld.Services services, Scenario scenario, UUID partnerId) throws Exception {
        EvalWorld world = EvalWorld.create(services, scenario, partnerId, 1, SETTINGS.agentProvider(),
                SETTINGS.agentModel());
        TurnTrace trace = world.dailyDigest(scenario.times() == null ? 1 : scenario.times());
        List<CheckResult> results = scenario.expectation() == null ? List.of()
                : new Checker(world.codes(), world::resolveDate).check("resumen", scenario.expectation(), trace);
        return new ScenarioRun(scenario, 1, results, String.valueOf(trace.state().latestDigest()));
    }

    private static CheckResult rubric(String prefix, Scenario.Turn turn, TurnTrace trace, Optional<Judge> judge) {
        String name = prefix + " · rúbrica";
        if (judge.isEmpty()) {
            return new CheckResult(name, Outcome.NO_JUDGE, turn.expectation().rubric());
        }
        List<String> tools = trace.tools().stream()
                .map(t -> t.name() + ": " + abbreviate(t.output(), 300)).toList();
        Judge.Judgement judgement = judge.get().judge(new Judge.Case(turn.message(), tools, trace.reply(),
                turn.expectation().rubric()));
        return switch (judgement.verdict()) {
            case MEETS -> CheckResult.pass(name, judgement.reason());
            case FAILS -> CheckResult.fail(name, judgement.reason());
            case INCONCLUSIVE -> new CheckResult(name, Outcome.INCONCLUSIVE, judgement.reason());
        };
    }

    private Optional<LlmJudge> judge() {
        if (SETTINGS.judgeModel().isEmpty()) {
            return Optional.empty();
        }
        String providerId = SETTINGS.judgeProvider().orElseGet(() -> providers.availableProviders().stream()
                .filter(p -> !p.equals(SETTINGS.agentProvider())).sorted().findFirst().orElse(SETTINGS.agentProvider()));
        Optional<LlmProvider> provider = providers.getProvider(providerId);
        if (provider.isEmpty()) {
            System.out.println("Juez: el proveedor '" + providerId + "' no está disponible (¿falta su clave?)");
            return Optional.empty();
        }
        return Optional.of(new LlmJudge(provider.get(), SETTINGS.judgeModel().get(),
                new SystemDefaultLlmCredential(providerId, environment)));
    }

    private static List<Scenario> select(List<Scenario> all) {
        return all.stream()
                .filter(s -> SETTINGS.task().map(t -> s.task().startsWith(t)).orElse(true))
                .filter(s -> SETTINGS.scenarioIds().isEmpty() || SETTINGS.scenarioIds().contains(s.id()))
                .toList();
    }

    private double spent(JdbcTemplate admin, Optional<LlmJudge> judge, Optional<PriceTable.Price> agentPrice,
                         Optional<PriceTable.Price> judgePrice) {
        Map<String, Object> u = admin.queryForMap("SELECT coalesce(sum(input_tokens), 0) AS i, "
                + "coalesce(sum(cache_read_tokens), 0) AS r, coalesce(sum(cache_write_tokens), 0) AS w, "
                + "coalesce(sum(output_tokens), 0) AS o FROM llm_usage_records");
        double agent = agentPrice.map(p -> p.cost(num(u, "i"), num(u, "r"), num(u, "w"), num(u, "o"))).orElse(0.0);
        double judging = judge.flatMap(j -> judgePrice.map(p -> p.cost(j.inputTokens(), 0, 0, j.outputTokens())))
                .orElse(0.0);
        return agent + judging;
    }

    private void writeOutputs(RunSummary summary, Optional<RunSummary> previous, List<ScenarioRun> runs, String now)
            throws Exception {
        String stamp = now.replace(":", "");
        Path out = SETTINGS.repoRoot().resolve("backend/cauce-evals/build/evals").resolve(stamp);
        Files.createDirectories(out);
        String markdown = Report.markdown(summary, previous, runs);
        Files.writeString(out.resolve("informe.md"), markdown, StandardCharsets.UTF_8);
        Files.writeString(out.resolve("resumen.json"), Report.json(summary), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("trazas.txt"), runs.stream()
                .map(r -> "== " + r.scenario().id() + " #" + r.repetition() + "\n" + r.transcript())
                .collect(Collectors.joining("\n\n")), StandardCharsets.UTF_8);
        System.out.println();
        System.out.println(markdown);
        System.out.println("Informe: " + out.resolve("informe.md"));
        if (SETTINGS.rehearsal()) {
            System.out.println("Simulacro: nada escrito en el historial.");
            return;
        }
        Path history = SETTINGS.evalsDir().resolve("historial");
        Files.createDirectories(history);
        Path summaryFile = history.resolve(stamp + "-" + SETTINGS.agentModel() + ".json");
        Files.writeString(summaryFile, Report.json(summary), StandardCharsets.UTF_8);
        System.out.println("Resumen para el historial (commitéalo): " + summaryFile);
    }

    private static void truncate(JdbcTemplate admin) {
        admin.execute("TRUNCATE TABLE audit_chain_heads, audit_log_entries, audit_outbox, llm_usage_records, "
                + "channel_inbound_receipts, owner_notifications, owner_recipients, owner_link_codes, daily_digests, "
                + "handoffs, bookings, agendas, knowledge_gaps, catalogs, business_profiles, channel_configs, "
                + "whatsapp_apps, api_keys, ingest_idempotency_records, pending_invocations, messages, conversations, "
                + "identities, agents, tenants CASCADE");
    }

    /** The sha256 of what the agent is: its prompts, its tool grant, its fallback and the fixture. */
    private static String footprint() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        Path pack = SETTINGS.packDir();
        List<Path> files = new ArrayList<>(List.of(pack.resolve("agente-clientes.prompt.md"),
                pack.resolve("agente-dueno.prompt.md"), pack.resolve("herramientas.json"), pack.resolve("respaldo.json")));
        try (Stream<Path> fixture = Files.list(pack.resolve("bar-ejemplo"))) {
            fixture.sorted().forEach(files::add);
        }
        for (Path file : files) {
            sha.update(Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /** The commit and whether the tree has uncommitted changes; "desconocido" without git. */
    private static String[] git() {
        try {
            String head = command("git", "rev-parse", "HEAD").strip();
            String status = command("git", "status", "--porcelain");
            return new String[] {head, String.valueOf(!status.isBlank())};
        } catch (Exception e) {
            return new String[] {"desconocido", "false"};
        }
    }

    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).directory(SETTINGS.repoRoot().toFile()).redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output;
    }

    private static boolean sameTools(List<String> a, List<String> b) {
        return a.stream().sorted().toList().equals(b.stream().sorted().toList());
    }

    private static CheckResult prefixed(String prefix, CheckResult result) {
        return new CheckResult(prefix + " · " + result.name(), result.outcome(), result.detail());
    }

    private static long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static String abbreviate(String text, int max) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
