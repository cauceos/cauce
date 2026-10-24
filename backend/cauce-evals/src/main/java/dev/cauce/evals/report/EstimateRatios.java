package dev.cauce.evals.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The context budget's estimate of each model call's input tokens read against what the provider
 * billed (ADR 0005, amendment of 2026-10-24): the ratio estimate / billed per call, summarized by
 * model. It is what calibrates the estimator's constants; the run writes it next to its report.
 */
public final class EstimateRatios {

    private EstimateRatios() {
    }

    /** One model call: what the budget estimated before it and what the provider billed. */
    public record Call(String provider, String model, int round, int estimated, int billed) {

        /** Estimate over billed; only defined for a call that billed input. */
        public double ratio() {
            return (double) estimated / billed;
        }
    }

    /** The ratio of a model's calls: how many, and their mean, minimum and maximum. */
    public record Summary(String provider, String model, int calls, double mean, double min, double max) {

        /** How far the mean is from an exact estimate, as a fraction: 0.12 is 12 % either way. */
        public double meanDeviation() {
            return Math.abs(mean - 1.0);
        }
    }

    /** Summaries by provider and model, in their order; calls that billed no input are left out. */
    public static List<Summary> summarize(List<Call> calls) {
        Map<String, List<Call>> byModel = calls.stream().filter(c -> c.billed() > 0)
                .collect(Collectors.groupingBy(c -> c.provider() + "/" + c.model(), TreeMap::new, Collectors.toList()));
        List<Summary> summaries = new ArrayList<>();
        byModel.forEach((key, modelCalls) -> {
            double[] ratios = modelCalls.stream().mapToDouble(Call::ratio).toArray();
            double sum = 0;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (double ratio : ratios) {
                sum += ratio;
                min = Math.min(min, ratio);
                max = Math.max(max, ratio);
            }
            Call first = modelCalls.get(0);
            summaries.add(new Summary(first.provider(), first.model(), ratios.length, sum / ratios.length, min, max));
        });
        return summaries;
    }

    /** One line per call, with a header: what a calibration reads back. */
    public static String csv(List<Call> calls) {
        StringBuilder csv = new StringBuilder("provider,model,round,estimated,billed\n");
        for (Call call : calls) {
            csv.append(call.provider()).append(',').append(call.model()).append(',').append(call.round()).append(',')
                    .append(call.estimated()).append(',').append(call.billed()).append('\n');
        }
        return csv.toString();
    }

    /** The report's section: one row per model. */
    public static String markdown(List<Summary> summaries) {
        StringBuilder md = new StringBuilder("## Estimación de tokens de entrada\n\n"
                + "Estimación del presupuesto de contexto entre lo facturado, por llamada (1,00 es exacto).\n\n");
        if (summaries.isEmpty()) {
            return md.append("Ninguna llamada facturó entrada.\n").toString();
        }
        md.append("| Modelo | Llamadas | Media | Mínimo | Máximo |\n|---|---|---|---|---|\n");
        for (Summary s : summaries) {
            md.append(String.format(Locale.ROOT, "| %s/%s | %d | %.3f | %.3f | %.3f |%n",
                    s.provider(), s.model(), s.calls(), s.mean(), s.min(), s.max()));
        }
        return md.toString();
    }
}
