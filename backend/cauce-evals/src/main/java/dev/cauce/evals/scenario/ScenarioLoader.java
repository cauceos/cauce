package dev.cauce.evals.scenario;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.cauce.evals.scenario.Scenario.Expectation;
import dev.cauce.evals.scenario.Scenario.ExpectedState;
import dev.cauce.evals.scenario.Scenario.Kind;
import dev.cauce.evals.scenario.Scenario.Setup;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads the scenarios of a pack's {@code evals} directory and validates them (ADR 0009, E3). The
 * format is closed: an unknown key, a value outside its vocabulary, a variable that names no
 * {@code previo}, or a scenario with neither turns, an action nor an IT is a load error with the
 * file and the scenario in its message, never a scenario that silently passes.
 */
public final class ScenarioLoader {

    static final Set<String> STATUSES = Set.of("HELD", "CONFIRMED", "CANCELLED");
    static final Set<String> CASE_STATUSES = Set.of("OPEN", "RESOLVED");
    static final Set<String> NOTICE_KINDS = Set.of("HANDOFF", "DAILY_DIGEST");
    static final Set<String> BOOKING_FIELDS = Set.of("estado", "fecha", "turno", "hora", "personas", "codigo",
            "contacto");
    static final Set<String> CASE_FIELDS = Set.of("categoria", "estado");
    static final Set<String> NOTICE_FIELDS = Set.of("tipo", "estado", "motivo");
    static final Set<String> CHANNELS = Set.of("api", "telegram");
    private static final Pattern TASK_FILE = Pattern.compile("\\d{2}-[a-z0-9-]+\\.yaml");
    private static final Pattern VARIABLE = Pattern.compile("\\$([a-z][a-z0-9_]*)\\.codigo");

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    /**
     * Every scenario of the task files ({@code NN-name.yaml}) directly in {@code directory}, by file
     * name; the other files there (prices, calibration) are not scenarios.
     */
    public List<Scenario> loadAll(Path directory) {
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing.filter(file -> TASK_FILE.matcher(file.getFileName().toString()).matches()).sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + directory, e);
        }
        List<Scenario> all = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Path file : files) {
            for (Scenario scenario : load(file)) {
                if (!ids.add(scenario.id())) {
                    throw new InvalidScenarioException(file + ": the id " + scenario.id() + " is used twice");
                }
                all.add(scenario);
            }
        }
        return all;
    }

    /** The scenarios of one file, validated. */
    public List<Scenario> load(Path file) {
        String task = file.getFileName().toString().replaceFirst("\\.yaml$", "");
        List<Scenario> scenarios;
        try {
            scenarios = yaml.readValue(file.toFile(), new TypeReference<List<Scenario>>() { });
        } catch (IOException e) {
            throw new InvalidScenarioException(file + ": " + e.getMessage(), e);
        }
        return scenarios.stream().map(s -> s.inTask(task)).peek(s -> validate(file, s)).toList();
    }

    private static void validate(Path file, Scenario s) {
        String where = file.getFileName() + " " + s.id() + ": ";
        require(s.id() != null && !s.id().isBlank(), file.getFileName() + ": a scenario without id");
        require(s.title() != null, where + "titulo is required");
        require(s.level() != null, where + "nivel is required (bloqueante | aviso)");
        require(s.fixture() != null, where + "datos is required");
        require(CHANNELS.contains(s.channel()), where + "canal must be one of " + CHANNELS);
        boolean hasTurns = !s.turns().isEmpty();
        require(!(hasTurns && s.action() != null), where + "turnos and accion are exclusive");
        require(hasTurns || s.action() != null || s.coveredBy() != null,
                where + "needs turnos, an accion or cubierto_por");
        if (s.action() != null) {
            require(Scenario.DAILY_DIGEST.equals(s.action()), where + "the only accion is " + Scenario.DAILY_DIGEST);
        }
        require(s.times() == null || s.action() != null && s.times() >= 1, where + "veces goes with an accion, >= 1");
        require(s.expectation() == null || s.kind() == Kind.DIGEST, where + "espera at scenario level is for an accion");

        Set<String> aliases = new HashSet<>();
        for (Setup setup : s.setup()) {
            int set = (setup.booking() != null ? 1 : 0) + (setup.handoff() != null ? 1 : 0)
                    + (setup.question() != null ? 1 : 0) + (setup.ownerLinked() != null ? 1 : 0);
            require(set == 1, where + "each previo entry holds exactly one of reserva, caso, pregunta, dueno_vinculado");
            if (setup.booking() != null) {
                Scenario.SetupBooking b = setup.booking();
                require(b.date() != null && RelativeDates.SPEC.matcher(b.date()).matches(),
                        where + "previo reserva fecha must be +N, proximo_<dia> or proximo_<dia>+N, not " + b.date());
                require(b.shift() != null && b.partySize() != null && b.partySize() >= 1,
                        where + "previo reserva needs turno and personas");
                require(STATUSES.contains(b.status()), where + "previo reserva estado must be one of " + STATUSES);
                require(b.time() == null || b.time().matches("\\d{2}:\\d{2}"), where + "hora must be HH:MM");
                if (b.alias() != null) {
                    aliases.add(b.alias());
                }
            }
            if (setup.handoff() != null) {
                require(setup.handoff().category() != null && CASE_STATUSES.contains(setup.handoff().status()),
                        where + "previo caso needs categoria and estado in " + CASE_STATUSES);
            }
        }
        for (Scenario.Turn turn : s.turns()) {
            require(turn.message() != null && !turn.message().isBlank(), where + "a turn without cliente");
            try {
                RelativeDates.validatePlaceholders(turn.message());
            } catch (InvalidScenarioException e) {
                throw new InvalidScenarioException(where + e.getMessage());
            }
            if (turn.expectation() != null) {
                validate(where, turn.expectation(), aliases, false);
            }
        }
        if (s.expectation() != null) {
            validate(where, s.expectation(), aliases, true);
        }
    }

    private static void validate(String where, Expectation e, Set<String> aliases, boolean digest) {
        if (!digest) {
            require(e.digestContains() == null && e.digestLacks() == null,
                    where + "resumen_contiene/resumen_no_contiene go with an accion");
        }
        ExpectedState state = e.state();
        if (state != null) {
            keysIn(where + "estado.reservas", state.bookingCounts(), STATUSES);
            keysIn(where + "estado.reserva", state.booking(), BOOKING_FIELDS);
            keysIn(where + "estado.casos", state.caseCounts(), CASE_STATUSES);
            keysIn(where + "estado.caso", state.handoff(), CASE_FIELDS);
            keysIn(where + "estado.avisos", state.noticeCounts(), NOTICE_KINDS);
            keysIn(where + "estado.aviso", state.notice(), NOTICE_FIELDS);
            if (state.booking() != null && state.booking().get("estado") != null) {
                require(STATUSES.contains(String.valueOf(state.booking().get("estado"))),
                        where + "estado.reserva.estado must be one of " + STATUSES);
            }
        }
        for (String value : variables(e)) {
            Matcher m = VARIABLE.matcher(value);
            while (m.find()) {
                require(aliases.contains(m.group(1)), where + "$" + m.group(1) + " names no previo (como: "
                        + m.group(1) + ")");
            }
        }
    }

    /** Every string of an expectation that may hold a {@code $name.codigo} variable. */
    private static List<String> variables(Expectation e) {
        List<String> values = new ArrayList<>();
        if (e.arguments() != null) {
            e.arguments().values().forEach(args -> args.values().forEach(v -> values.add(String.valueOf(v))));
        }
        if (e.replyContains() != null) {
            values.addAll(e.replyContains());
        }
        if (e.replyLacks() != null) {
            values.addAll(e.replyLacks());
        }
        if (e.state() != null && e.state().booking() != null) {
            e.state().booking().values().forEach(v -> values.add(String.valueOf(v)));
        }
        return values;
    }

    private static void keysIn(String where, Map<String, ?> map, Set<String> allowed) {
        if (map != null) {
            for (String key : map.keySet()) {
                require(allowed.contains(key), where + ": '" + key + "' is not one of " + allowed);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidScenarioException(message);
        }
    }
}
