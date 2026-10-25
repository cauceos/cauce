package dev.cauce.evals.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioSelectionTest {

    @TempDir
    Path dir;

    private List<Scenario> all;

    @BeforeEach
    void loadTwoTasks() throws Exception {
        Files.writeString(dir.resolve("01-informacion.yaml"), scenario("H1.1"));
        Files.writeString(dir.resolve("03-reservas.yaml"), scenario("H3.1") + scenario("H3.2"));
        all = new ScenarioLoader().loadAll(dir);
    }

    @Test
    void select_byIdsAndTasks_keepsTheMatching_andNothingSelectedIsEverything() {
        assertThat(ScenarioSelection.select(all, List.of(), List.of("H3.2", "H1.1")))
                .extracting(Scenario::id).containsExactly("H1.1", "H3.2");
        assertThat(ScenarioSelection.select(all, List.of("03"), List.of())).extracting(Scenario::id)
                .containsExactly("H3.1", "H3.2");
        assertThat(ScenarioSelection.select(all, List.of(), List.of())).hasSize(3);
    }

    @Test
    void select_anIdThatIsNoScenarios_failsNamingIt_insteadOfRunningTheRest() {
        assertThatThrownBy(() -> ScenarioSelection.select(all, List.of(), List.of("H3.1", "H3.15", "H3.16")))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("escenarios H3.15, H3.16;")
                .hasMessageNotContaining("H3.1,");
    }

    @Test
    void select_aTaskThatIsNoTasks_failsNamingIt() {
        assertThatThrownBy(() -> ScenarioSelection.select(all, List.of("03", "07"), List.of()))
                .isInstanceOf(InvalidScenarioException.class)
                .hasMessageContaining("tareas 07;")
                .hasMessageContaining("01-informacion, 03-reservas");
    }

    private static String scenario(String id) {
        return """
                - id: %s
                  titulo: t
                  nivel: aviso
                  datos: bar-ejemplo
                  turnos:
                    - cliente: "hola"
                """.formatted(id);
    }
}
