package dev.cauce.evals.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.evals.scenario.Scenario.Kind;
import dev.cauce.evals.scenario.Scenario.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioLoaderTest {

    /** Gradle runs the tests from the module directory, {@code backend/cauce-evals}. */
    private static final Path PACK_EVALS = Path.of("..", "..", "examples", "hosteleria", "evals");

    private final ScenarioLoader loader = new ScenarioLoader();

    @TempDir
    Path dir;

    @Test
    void loadAll_acceptsEveryScenarioOfTheHospitalityPack_asTheyAre() {
        List<Scenario> all = loader.loadAll(PACK_EVALS);

        assertThat(all).extracting(Scenario::id).contains("G1", "H1.5", "H3.10", "H3.11", "H4.6", "H4.7", "H5.7");
        assertThat(all).filteredOn(s -> s.id().equals("H3.11")).singleElement().satisfies(s -> {
            assertThat(s.task()).isEqualTo("03-reservas");
            assertThat(s.kind()).isEqualTo(Kind.CONVERSATION);
            assertThat(s.setup().get(0).booking().alias()).isEqualTo("r");
        });
        assertThat(all).filteredOn(s -> s.id().equals("H5.2")).singleElement()
                .satisfies(s -> assertThat(s.times()).isEqualTo(2));
        assertThat(all).filteredOn(s -> s.id().equals("H3.7")).singleElement()
                .satisfies(s -> assertThat(s.kind()).isEqualTo(Kind.COVERED_BY_IT));
        assertThat(all).filteredOn(s -> s.id().equals("G1")).singleElement()
                .satisfies(s -> assertThat(s.level()).isEqualTo(Level.BLOCKING));
        // ADR 0012: a turn may be a transcribed voice note; the next one is written.
        assertThat(all).filteredOn(s -> s.id().equals("H3.14")).singleElement().satisfies(s -> {
            assertThat(s.turns().get(0).isVoiceNote()).isTrue();
            assertThat(s.turns().get(1).isVoiceNote()).isFalse();
        });
    }

    @Test
    void anEarlierThread_needsItsDaysAndBothTexts() throws Exception {
        Scenario loaded = loader.load(file("""
                - id: X5
                  titulo: t
                  nivel: bloqueante
                  datos: bar-ejemplo
                  previo:
                    - hilo_anterior: {hace_dias: 2, cliente: "¿Abrís los lunes?", agente: "Hoy es {+0}: los lunes cerramos."}
                  turnos:
                    - cliente: "Mesa para dos mañana"
                """)).get(0);

        assertThat(loaded.setup().get(0).earlierThread()).satisfies(t -> {
            assertThat(t.daysAgo()).isEqualTo(2);
            assertThat(t.hasClockLookup()).isTrue();
        });
        assertThatThrownBy(() -> loader.load(file("""
                - id: X6
                  titulo: t
                  nivel: aviso
                  datos: bar-ejemplo
                  previo:
                    - hilo_anterior: {hace_dias: 0, cliente: "hola", agente: "buenas"}
                  turnos:
                    - cliente: "hola"
                """))).isInstanceOf(InvalidScenarioException.class).hasMessageContaining("hace_dias");
    }

    @Test
    void anUnknownKey_isALoadError_notAScenarioThatPasses() throws Exception {
        assertThatThrownBy(() -> loader.load(file("""
                - id: X1
                  titulo: t
                  nivel: aviso
                  datos: bar-ejemplo
                  turnos:
                    - cliente: "hola"
                      espera:
                        herramienta: [search_catalog]
                """))).isInstanceOf(InvalidScenarioException.class).hasMessageContaining("herramienta");
    }

    @Test
    void aStateKeyOutsideTheVocabulary_andAVariableWithoutItsPrevio_areRejected() throws Exception {
        assertThatThrownBy(() -> loader.load(file("""
                - id: X2
                  titulo: t
                  nivel: aviso
                  datos: bar-ejemplo
                  turnos:
                    - cliente: "hola"
                      espera:
                        estado: {reservas: {PENDING: 1}}
                """))).isInstanceOf(InvalidScenarioException.class).hasMessageContaining("PENDING");
        assertThatThrownBy(() -> loader.load(file("""
                - id: X3
                  titulo: t
                  nivel: aviso
                  datos: bar-ejemplo
                  turnos:
                    - cliente: "hola"
                      espera:
                        respuesta_contiene: [$r.codigo]
                """))).isInstanceOf(InvalidScenarioException.class).hasMessageContaining("$r");
    }

    @Test
    void aScenarioWithNothingToRun_isRejected() throws Exception {
        assertThatThrownBy(() -> loader.load(file("""
                - id: X4
                  titulo: t
                  nivel: bloqueante
                  datos: bar-ejemplo
                """))).isInstanceOf(InvalidScenarioException.class).hasMessageContaining("cubierto_por");
    }

    private Path file(String yaml) throws Exception {
        Path file = dir.resolve("99-prueba.yaml");
        Files.writeString(file, yaml);
        return file;
    }
}
