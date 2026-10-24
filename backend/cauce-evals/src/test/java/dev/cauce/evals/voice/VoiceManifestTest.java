package dev.cauce.evals.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceManifestTest {

    @TempDir
    Path folder;

    @Test
    void load_readsEachNoteWithItsCriticalData_inOrder() throws Exception {
        Files.writeString(folder.resolve("V01.ogg"), "OggS");
        Files.writeString(folder.resolve("V02.ogg"), "OggS");
        manifest("""
                - id: V01
                  fichero: V01.ogg
                  transcripcion: "Mesa para cuatro, soy Ana López"
                  datos_criticos: ["cuatro", "Ana López"]
                  notas: "ruido de fondo"
                - id: V02
                  fichero: V02.ogg
                  transcripcion: "¿Las bravas llevan gluten?"
                """);

        List<VoiceManifest.Note> notes = VoiceManifest.load(folder);

        assertThat(notes).extracting(VoiceManifest.Note::id).containsExactly("V01", "V02");
        assertThat(notes.get(0).criticalData()).containsExactly("cuatro", "Ana López");
        assertThat(notes.get(1).criticalData()).isEmpty();
    }

    @Test
    void load_aMissingFileARepeatedIdAnUnknownKeyOrAnEmptyDatum_isALoadError() throws Exception {
        Files.writeString(folder.resolve("V01.ogg"), "OggS");
        manifest("""
                - id: V01
                  fichero: V09.ogg
                  transcripcion: "hola"
                """);
        assertThatThrownBy(() -> VoiceManifest.load(folder)).hasMessageContaining("no file V09.ogg");
        manifest("""
                - {id: V01, fichero: V01.ogg, transcripcion: "hola"}
                - {id: V01, fichero: V01.ogg, transcripcion: "hola"}
                """);
        assertThatThrownBy(() -> VoiceManifest.load(folder)).hasMessageContaining("repeated");
        manifest("""
                - {id: V01, fichero: V01.ogg, transcripcion: "hola", idioma: es}
                """);
        assertThatThrownBy(() -> VoiceManifest.load(folder)).hasMessageContaining("idioma");
        manifest("""
                - {id: V01, fichero: V01.ogg, transcripcion: "hola", datos_criticos: ["  "]}
                """);
        assertThatThrownBy(() -> VoiceManifest.load(folder)).hasMessageContaining("datos_criticos is empty");
    }

    private void manifest(String yaml) throws Exception {
        Files.writeString(folder.resolve(VoiceManifest.FILE), yaml);
    }
}
