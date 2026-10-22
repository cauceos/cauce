package dev.cauce.evals.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The summary of a run that is committed to the pack's {@code historial/} (ADR 0009, E11): what ran,
 * with which model, prompt and commit, and every rate. Full transcripts stay out of git. A later
 * run is compared with the previous summary of the same pack.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunSummary(
        @JsonProperty("fecha") String date,
        @JsonProperty("commit") String commit,
        @JsonProperty("arbol_con_cambios") boolean dirtyTree,
        @JsonProperty("modelo") String agentModel,
        @JsonProperty("juez") String judge,
        @JsonProperty("calibracion") String calibration,
        @JsonProperty("huella_prompt") String promptFootprint,
        @JsonProperty("n") int repetitions,
        @JsonProperty("seleccion") String selection,
        @JsonProperty("completa") boolean complete,
        @JsonProperty("tokens") Tokens tokens,
        @JsonProperty("coste_usd") Double costUsd,
        @JsonProperty("precios") String priceVersion,
        @JsonProperty("tasa_global") double overallRate,
        @JsonProperty("reglas") Map<String, Double> generalRules,
        @JsonProperty("tareas") Map<String, Double> tasks,
        @JsonProperty("escenarios") List<ScenarioRate> scenarios,
        @JsonProperty("bloqueantes_fallidos") List<String> blockingFailures,
        @JsonProperty("avisos") List<String> warnings) {

    /** Tokens of the run, the agent's and the judge's. {@code entrada} includes the cached parts. */
    public record Tokens(
            @JsonProperty("entrada") long input,
            @JsonProperty("cache_lectura") long cacheRead,
            @JsonProperty("cache_escritura") long cacheWrite,
            @JsonProperty("salida") long output,
            @JsonProperty("turnos_modelo") int modelTurns,
            @JsonProperty("juez_entrada") long judgeInput,
            @JsonProperty("juez_salida") long judgeOutput) {
    }

    /** One scenario's rate over its repetitions, and each of its checks'. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScenarioRate(
            @JsonProperty("id") String id,
            @JsonProperty("tarea") String task,
            @JsonProperty("nivel") String level,
            @JsonProperty("tasa") Double rate,
            @JsonProperty("repeticiones") int repetitions,
            @JsonProperty("comprobaciones") Map<String, Double> checks,
            @JsonProperty("sin_juez") int withoutJudge,
            @JsonProperty("estado") String status) {
    }
}
