package dev.cauce.evals.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptionScoreTest {

    private static final String SAID = "Hola, quería mesa para cuatro. Soy Ana López, seis uno dos tres cuatro cinco"
            + " seis siete ocho.";
    private static final List<String> CRITICAL = List.of("cuatro", "Ana López",
            "seis uno dos tres cuatro cinco seis siete ocho");

    @Test
    void of_aTranscriptionThatHeardEverything_inFigures_isPerfect() {
        TranscriptionScore score = TranscriptionScore.of(SAID,
                "Hola, quería mesa para 4. Soy Ana López, 612-345-678.", CRITICAL);

        assertThat(score.wordErrorRate()).isZero();
        assertThat(score.criticalDataRight()).isTrue();
    }

    @Test
    void of_aMisheardName_costsItsWords_andTheNameAsACriticalDatum() {
        TranscriptionScore score = TranscriptionScore.of(SAID,
                "Hola, quería mesa para 4. Soy Analo P.C., 612-345-678.", CRITICAL);

        // "ana lopez" heard as "analo p c": 2 substitutions and an insertion, of 17 words.
        assertThat(score.expectedWords()).isEqualTo(17);
        assertThat(score.wordErrorRate()).isCloseTo(3.0 / 17, within(1e-9));
        assertThat(score.criticalData()).containsEntry("Ana López", false)
                .containsEntry("seis uno dos tres cuatro cinco seis siete ocho", true).containsEntry("cuatro", true);
        assertThat(score.criticalDataRight()).isFalse();
    }

    @Test
    void of_aPhoneMissingADigit_isNotHeardRight() {
        TranscriptionScore score = TranscriptionScore.of(SAID, "Hola, quería mesa para 4. Soy Ana López, 612-345-67.",
                CRITICAL);

        assertThat(score.criticalData()).containsEntry("seis uno dos tres cuatro cinco seis siete ocho", false);
    }

    @Test
    void of_aCriticalWord_toleratesItsPluralCaseAndAccents_butANumberOnlyAsItself() {
        assertThat(TranscriptionScore.of("¿Se puede ir con perro?", "Se puede ir con perros.", List.of("perro"))
                .criticalDataRight()).isTrue();
        assertThat(TranscriptionScore.of("Para las mesas", "para la MESA", List.of("mesas")).criticalDataRight()).isTrue();
        assertThat(TranscriptionScore.of("Mi niño es celíaco", "mi nino es celiaco", List.of("celíaco"))
                .criticalDataRight()).isTrue();
        assertThat(TranscriptionScore.of("Vamos tres", "vamos treses", List.of("tres")).criticalDataRight()).isFalse();
        assertThat(TranscriptionScore.of("Para seis", "para 16", List.of("seis")).criticalDataRight()).isFalse();
    }

    @Test
    void editDistance_countsSubstitutionsDeletionsAndInsertions() {
        assertThat(TranscriptionScore.editDistance(List.of("a", "b", "c"), List.of("a", "x", "c", "d"))).isEqualTo(2);
        assertThat(TranscriptionScore.editDistance(List.of("a", "b"), List.of())).isEqualTo(2);
    }

    @Test
    void report_weighsEachNoteByItsWords_andCountsTheCriticalData() {
        VoiceManifest.Note note = new VoiceManifest.Note("V01", "v.ogg", SAID, CRITICAL, null);
        VoiceReport.Result perfect = new VoiceReport.Result(note, "x", 4, 2.0, TranscriptionScore.of(SAID,
                "Hola, quería mesa para 4. Soy Ana López, 612-345-678.", CRITICAL));
        VoiceReport.Result misheard = new VoiceReport.Result(note, "y", 4, 3.0, TranscriptionScore.of(SAID,
                "Hola, quería mesa para 4. Soy Analo P.C., 612-345-678.", CRITICAL));

        assertThat(VoiceReport.overallWordErrorRate(List.of(perfect, misheard))).isCloseTo(3.0 / 34, within(1e-9));
        assertThat(VoiceReport.criticalDataRight(List.of(perfect, misheard))).containsExactly(5, 6);
        assertThat(VoiceReport.meanTranscriptionSeconds(List.of(perfect, misheard))).isEqualTo(2.5);
        assertThat(VoiceReport.markdown("Voz", List.of(misheard))).contains("| V01 | 4 | 3.0 s | 17.6 % |", "«Ana López» ✗");
        assertThat(VoiceReport.csv(List.of(misheard)))
                .startsWith("id,seconds,transcription_seconds,expected_words,word_error_rate,critical_right")
                .contains("V01,4,3.00,17,0.1765,2,3,Ana López\n");
    }
}
