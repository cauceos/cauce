package dev.cauce.evals.judge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.evals.judge.Calibration.LabelledCase;
import dev.cauce.evals.judge.Calibration.Status;
import dev.cauce.evals.judge.Judge.Judgement;
import dev.cauce.evals.judge.Judge.Verdict;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CalibrationTest {

    @Test
    void thePacksCases_load_andWhilePendingTheJudgeIsUncalibrated() {
        List<LabelledCase> cases = Calibration.load(Path.of("..", "..", "examples", "hosteleria", "evals", "calibracion"));

        assertThat(cases).extracting(LabelledCase::id).contains("C1", "C6", "C8");
        assertThat(Calibration.calibrate(fixed(t -> Verdict.MEETS), cases.stream()
                .filter(c -> !c.labelled()).toList()).status()).isEqualTo(Status.UNCALIBRATED);
    }

    @Test
    void aJudge_isAcceptedOnlyIfItsMajorityMatchesEveryLabel() {
        List<LabelledCase> cases = List.of(labelled("C1", "no_cumple"), labelled("C2", "cumple"));

        Calibration.Result agrees = Calibration.calibrate(fixed(t -> t.message().equals("C2") ? Verdict.MEETS
                : Verdict.FAILS), cases);
        Calibration.Result disagrees = Calibration.calibrate(fixed(t -> Verdict.MEETS), cases);

        assertThat(agrees.accepted()).isTrue();
        assertThat(disagrees.status()).isEqualTo(Status.REJECTED);
        assertThat(disagrees.disagreements()).singleElement().asString().startsWith("C1");
    }

    @Test
    void anInconclusiveJudge_isNeverAccepted() {
        assertThat(Calibration.calibrate(fixed(t -> Verdict.INCONCLUSIVE), List.of(labelled("C1", "cumple"))).accepted())
                .isFalse();
    }

    private static LabelledCase labelled(String id, String label) {
        return new LabelledCase(id, "o", "H1.5", id, List.of(), "respuesta", "rúbrica", label, "");
    }

    private static Judge fixed(Function<Judge.Case, Verdict> verdict) {
        return new Judge() {
            @Override
            public Judgement judge(Case turn) {
                return new Judgement(verdict.apply(turn), "");
            }

            @Override
            public String describe() {
                return "fijo";
            }
        };
    }
}
