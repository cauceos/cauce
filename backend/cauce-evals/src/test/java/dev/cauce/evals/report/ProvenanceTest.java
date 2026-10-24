package dev.cauce.evals.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProvenanceTest {

    private static final String COMMIT = "64ca30e850863fbfe093bd3e93a3caec241ec664";

    @Test
    void line_ofATreeUpToDate_namesItsCommit_andNoWarning() {
        Provenance provenance = new Provenance(COMMIT, false, "origin/main", 0);

        assertThat(provenance.line()).isEqualTo("Commit: 64ca30e · al día con origin/main");
        assertThat(provenance.warning()).isEmpty();
    }

    @Test
    void warning_ofATreeBehindItsBranch_saysWhatItMeasuresInstead() {
        Provenance provenance = new Provenance(COMMIT, true, "origin/main", 9);

        assertThat(provenance.line())
                .isEqualTo("Commit: 64ca30e (con cambios sin commitear) · 9 commits por detrás de origin/main");
        assertThat(provenance.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .contains("9 commits por detrás de origin/main", "64ca30e", "git checkout --detach origin/main"));
    }

    @Test
    void line_whenGitCannotTell_saysSo_withoutWarning() {
        Provenance provenance = new Provenance("desconocido", false, "origin/main", null);

        assertThat(provenance.line()).isEqualTo("Commit: desconocido · sin saber si está al día con origin/main");
        assertThat(provenance.warning()).isEmpty();
        assertThat(new Provenance(COMMIT, false, "origin/main", 1).line()).endsWith("1 commit por detrás de origin/main");
    }
}
