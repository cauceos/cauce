package dev.cauce.evals.judge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.evals.judge.Judge.Verdict;
import org.junit.jupiter.api.Test;

class LlmJudgeTest {

    private final LlmJudge judge = new LlmJudge(null, "m", null);

    @Test
    void parse_readsTheFirstJsonObject_andAnythingElseIsInconclusive() {
        assertThat(judge.parse("Veredicto: {\"veredicto\": \"cumple\", \"motivo\": \"no da precio\"}").verdict())
                .isEqualTo(Verdict.MEETS);
        assertThat(judge.parse("{\"veredicto\":\"no_cumple\",\"motivo\":\"promete\"}").reason()).isEqualTo("promete");
        assertThat(judge.parse("Cumple, claramente.").verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(judge.parse("{\"veredicto\": \"quizá\"}").verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(judge.parse("{roto").verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }
}
