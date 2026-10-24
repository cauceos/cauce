package dev.cauce.evals.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.cauce.evals.report.EstimateRatios.Call;
import dev.cauce.evals.report.EstimateRatios.Summary;
import java.util.List;
import org.junit.jupiter.api.Test;

class EstimateRatiosTest {

    private static final List<Call> CALLS = List.of(
            new Call("anthropic", "claude-sonnet-5-5", 0, 1100, 1000),
            new Call("anthropic", "claude-sonnet-5-5", 1, 1300, 1000),
            new Call("anthropic", "claude-sonnet-5-5", 2, 900, 1000),
            new Call("anthropic", "claude-haiku-5-5", 0, 500, 1000),
            new Call("anthropic", "claude-haiku-5-5", 1, 50, 0));

    @Test
    void summarize_meanMinAndMaxOfTheRatioByModel_leavingOutCallsThatBilledNothing() {
        List<Summary> summaries = EstimateRatios.summarize(CALLS);

        assertThat(summaries).extracting(Summary::model).containsExactly("claude-haiku-5-5", "claude-sonnet-5-5");
        Summary haiku = summaries.get(0);
        assertThat(haiku.calls()).isEqualTo(1);
        assertThat(haiku.mean()).isCloseTo(0.5, within(1e-9));
        Summary sonnet = summaries.get(1);
        assertThat(sonnet.calls()).isEqualTo(3);
        assertThat(sonnet.mean()).isCloseTo(1.1, within(1e-9));
        assertThat(sonnet.min()).isCloseTo(0.9, within(1e-9));
        assertThat(sonnet.max()).isCloseTo(1.3, within(1e-9));
        assertThat(sonnet.meanDeviation()).isCloseTo(0.1, within(1e-9));
    }

    @Test
    void csv_hasOneLinePerCallUnderAHeader() {
        assertThat(EstimateRatios.csv(CALLS.subList(0, 1)))
                .isEqualTo("provider,model,round,estimated,billed\nanthropic,claude-sonnet-5-5,0,1100,1000\n");
    }

    @Test
    void markdown_oneRowPerModel() {
        assertThat(EstimateRatios.markdown(EstimateRatios.summarize(CALLS)))
                .contains("| anthropic/claude-sonnet-5-5 | 3 | 1.100 | 0.900 | 1.300 |");
        assertThat(EstimateRatios.markdown(List.of())).contains("Ninguna llamada facturó entrada.");
    }
}
