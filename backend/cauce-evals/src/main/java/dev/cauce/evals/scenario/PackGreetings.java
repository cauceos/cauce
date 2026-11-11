package dev.cauce.evals.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The greetings of a pack's shop-assistant mode, from its {@code asistente.json} (ADR 0014, WC8): the
 * template a web chat shows when it opens by itself on a signal. The harness greets a turn opened on a
 * signal ({@code abierto_por}) with it, whatever the pack's launch phase: a scenario of the opening on its
 * own is what the pack must pass before {@code proactive_opening} is turned on, so it is played while that
 * is still off. A pack without the file has no greetings.
 */
public record PackGreetings(Map<String, String> bySignal) {

    public static final String FILE = "asistente.json";

    public PackGreetings {
        bySignal = Map.copyOf(bySignal);
    }

    /** The greetings of the pack at {@code packDir}. */
    public static PackGreetings of(Path packDir) {
        Path file = packDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return new PackGreetings(Map.of());
        }
        try {
            JsonNode templates = new ObjectMapper().readTree(file.toFile()).path("templates");
            Map<String, String> bySignal = new TreeMap<>();
            templates.properties().forEach(entry -> {
                if (entry.getValue().isTextual() && !entry.getValue().asText().isBlank()) {
                    bySignal.put(entry.getKey(), entry.getValue().asText().strip());
                }
            });
            return new PackGreetings(bySignal);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** The greeting of a chat that opened by itself on {@code signal}. */
    public Optional<String> forSignal(String signal) {
        return signal == null ? Optional.empty() : Optional.ofNullable(bySignal.get(signal));
    }
}
