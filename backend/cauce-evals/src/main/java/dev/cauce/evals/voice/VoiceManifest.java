package dev.cauce.evals.voice;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The voice notes a transcription is measured on (ADR 0012): a folder of audio files, kept out of
 * the public repo, and its {@code manifiesto.yaml} — for each note, its file, what is said in it
 * word for word, and optionally the critical data a mishearing must not change, each written as it
 * is said ("cuatro", "Ana", "seis cero cero ...", "tarta de queso"). Closed like the scenario
 * format: an unknown key, a missing file or a repeated id is a load error, never a note that
 * silently passes.
 *
 * <pre>
 * - id: "01"
 *   fichero: nota-01.ogg
 *   transcripcion: "Buenas, ¿tenéis mesa para cuatro el sábado por la noche?"
 *   datos_criticos: ["cuatro", "sábado", "noche"]
 *   notas: "ruido de fondo"
 * </pre>
 */
public final class VoiceManifest {

    /** The manifest's file name in the folder. */
    public static final String FILE = "manifiesto.yaml";

    /** One voice note: its file in the folder, what it says, and what must be heard right. */
    public record Note(
            @JsonProperty("id") String id,
            @JsonProperty("fichero") String file,
            @JsonProperty("transcripcion") String transcript,
            @JsonProperty("datos_criticos") List<String> criticalData,
            @JsonProperty("notas") String notes) {

        public Note {
            criticalData = criticalData == null ? List.of() : List.copyOf(criticalData);
        }
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private VoiceManifest() {
    }

    /**
     * The notes of {@code folder}'s manifest, in its order.
     *
     * @throws IllegalArgumentException if the manifest is malformed, names a file that is not there,
     *     repeats an id, or has an empty critical datum
     */
    public static List<Note> load(Path folder) {
        Path manifest = folder.resolve(FILE);
        List<Note> notes;
        try {
            notes = YAML.readValue(manifest.toFile(), new TypeReference<List<Note>>() { });
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + manifest + ": " + e.getMessage(), e);
        }
        if (notes == null || notes.isEmpty()) {
            throw new IllegalArgumentException(manifest + " lists no voice note");
        }
        Set<String> ids = new HashSet<>();
        for (Note note : notes) {
            String where = manifest.getFileName() + ", " + (note.id() == null ? "a note without id" : note.id()) + ": ";
            require(note.id() != null && !note.id().isBlank(), where + "id is missing");
            require(ids.add(note.id()), where + "the id is repeated");
            require(note.file() != null && !note.file().isBlank(), where + "fichero is missing");
            require(Files.isRegularFile(folder.resolve(note.file())), where + "no file " + note.file() + " in " + folder);
            require(note.transcript() != null && !note.transcript().isBlank(), where + "transcripcion is missing");
            for (String datum : note.criticalData()) {
                require(datum != null && !datum.isBlank(), where + "a critical datum in datos_criticos is empty");
            }
        }
        return List.copyOf(notes);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
