package dev.cauce.evals.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProvenanceTest {

    private static final String COMMIT = "64ca30e850863fbfe093bd3e93a3caec241ec664";
    private static final String PACK = "examples/hosteleria/";

    @Test
    void line_ofATreeUpToDate_namesItsCommit_andNoWarning() {
        Provenance provenance = new Provenance(COMMIT, List.of(), "origin/main", 0);

        assertThat(provenance.line()).isEqualTo("Commit: 64ca30e · al día con origin/main");
        assertThat(provenance.warning()).isEmpty();
        assertThat(provenance.problem(PACK)).isEmpty();
    }

    @Test
    void warning_ofATreeBehindItsBranch_saysWhatItMeasuresInstead() {
        Provenance provenance = new Provenance(COMMIT, List.of("README.md"), "origin/main", 9);

        assertThat(provenance.line())
                .isEqualTo("Commit: 64ca30e (con cambios sin commitear) · 9 commits por detrás de origin/main");
        assertThat(provenance.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .contains("9 commits por detrás de origin/main", "64ca30e", "git checkout --detach origin/main"));
        assertThat(provenance.problem(PACK)).hasValue("El árbol (64ca30e) va 9 commits por detrás de origin/main.");
    }

    @Test
    void line_whenGitCannotTell_saysSo_withoutWarning() {
        Provenance provenance = new Provenance("desconocido", List.of(), "origin/main", null);

        assertThat(provenance.line()).isEqualTo("Commit: desconocido · sin saber si está al día con origin/main");
        assertThat(provenance.warning()).isEmpty();
        assertThat(provenance.problem(PACK)).isEmpty();
        assertThat(new Provenance(COMMIT, List.of(), "origin/main", 1).line()).endsWith("1 commit por detrás de origin/main");
    }

    @Test
    void problem_aChangeToThePrompt_orAScenario_isOne_butTheHistoryAndTheRestAreNot() {
        Provenance changed = new Provenance(COMMIT, List.of("examples/hosteleria/agente-clientes.prompt.md",
                "examples/hosteleria/evals/03-reservas.yaml", "examples/hosteleria/evals/historial/x.json",
                "backend/build.gradle.kts"), "origin/main", 0);
        Provenance onlyHistory = new Provenance(COMMIT, List.of("examples/hosteleria/evals/historial/x.json",
                "QUICKSTART.md"), "origin/main", 0);

        assertThat(changed.problem("examples/hosteleria")).hasValue("El árbol (64ca30e) tiene cambios sin commitear "
                + "en el paquete: examples/hosteleria/agente-clientes.prompt.md, examples/hosteleria/evals/03-reservas.yaml.");
        assertThat(onlyHistory.problem(PACK)).isEmpty();
        assertThat(new Provenance(COMMIT, changed.changed(), "origin/main", 2).problem(PACK))
                .hasValueSatisfying(problem -> assertThat(problem).contains("va 2 commits por detrás", " y tiene cambios"));
    }
}
