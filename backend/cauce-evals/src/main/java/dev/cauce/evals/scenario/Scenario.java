package dev.cauce.evals.scenario;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * One scenario of a sector pack, in the closed format v2 (ADR 0009, E3): either a conversation
 * ({@code turnos}), a deterministic action ({@code accion}), or only a reference to the IT that
 * covers it ({@code cubierto_por}). The YAML keys are the pack's language; {@link ScenarioLoader}
 * rejects any key this type does not define and validates the rest.
 *
 * @param task the file it was loaded from, without extension ({@code 03-reservas}); not in the YAML
 */
public record Scenario(
        @JsonProperty("id") String id,
        @JsonProperty("titulo") String title,
        @JsonProperty("nivel") Level level,
        @JsonProperty("datos") String fixture,
        @JsonProperty("canal") String channel,
        @JsonProperty("origen") String origin,
        @JsonProperty("previo") List<Setup> setup,
        @JsonProperty("turnos") List<Turn> turns,
        @JsonProperty("accion") String action,
        @JsonProperty("veces") Integer times,
        @JsonProperty("espera") Expectation expectation,
        @JsonProperty("cubierto_por") String coveredBy,
        @JsonProperty("nota") String note,
        String task) {

    /** The only deterministic action a scenario can run instead of turns. */
    public static final String DAILY_DIGEST = "resumen_de_hoy";

    public Scenario {
        setup = setup == null ? List.of() : List.copyOf(setup);
        turns = turns == null ? List.of() : List.copyOf(turns);
        channel = channel == null ? "api" : channel;
    }

    /** What a failure of the scenario means for a run (E7). */
    public enum Level {
        /** Unacceptable: 100 % of the repetitions, or the run fails. */
        @JsonProperty("bloqueante") BLOCKING,
        /** Measured against a configurable threshold; a lower rate warns and blocks nothing. */
        @JsonProperty("aviso") WARNING
    }

    /** How the harness treats this scenario. */
    public enum Kind {
        CONVERSATION,
        DIGEST,
        COVERED_BY_IT
    }

    public Kind kind() {
        if (!turns.isEmpty()) {
            return Kind.CONVERSATION;
        }
        return action != null ? Kind.DIGEST : Kind.COVERED_BY_IT;
    }

    /** This scenario as loaded from {@code task}. */
    public Scenario inTask(String task) {
        return new Scenario(id, title, level, fixture, channel, origin, setup, turns, action, times, expectation,
                coveredBy, note, task);
    }

    /** One entry of {@code previo}: exactly one of its fields is set. */
    public record Setup(
            @JsonProperty("reserva") SetupBooking booking,
            @JsonProperty("caso") SetupCase handoff,
            @JsonProperty("pregunta") String question,
            @JsonProperty("dueno_vinculado") Boolean ownerLinked) {
    }

    /** A booking that exists before the first turn. {@code como} names it for {@code $name.codigo}. */
    public record SetupBooking(
            @JsonProperty("fecha") String date,
            @JsonProperty("turno") String shift,
            @JsonProperty("hora") String time,
            @JsonProperty("personas") Integer partySize,
            @JsonProperty("estado") String status,
            @JsonProperty("contacto") String contactName,
            @JsonProperty("mismo_cliente") Boolean sameCustomer,
            @JsonProperty("como") String alias) {
    }

    /** An open case that exists before the first turn. */
    public record SetupCase(
            @JsonProperty("categoria") String category,
            @JsonProperty("contacto") String contactName,
            @JsonProperty("estado") String status,
            @JsonProperty("resumen") String summary) {
    }

    /**
     * One message of the person and what must hold after the agent answers it. With
     * {@code nota_de_voz: true} the message is the transcription of a voice note (ADR 0012): it
     * reaches the agent as a transcribed voice note, as it would from a channel, so a scenario tests
     * what the agent does with what was heard, mistakes included.
     */
    public record Turn(
            @JsonProperty("cliente") String message,
            @JsonProperty("nota_de_voz") Boolean voiceNote,
            @JsonProperty("espera") Expectation expectation) {

        /** A written message. */
        public Turn(String message, Expectation expectation) {
            this(message, null, expectation);
        }

        /** Whether the message is the transcription of a voice note. */
        public boolean isVoiceNote() {
            return Boolean.TRUE.equals(voiceNote);
        }
    }

    /** The checks of a turn, or of a scenario's action. Every field is optional. */
    public record Expectation(
            @JsonProperty("herramientas") List<String> tools,
            @JsonProperty("orden") List<String> order,
            @JsonProperty("herramientas_no") List<String> forbiddenTools,
            @JsonProperty("argumentos") Map<String, Map<String, Object>> arguments,
            @JsonProperty("respuesta_contiene") List<String> replyContains,
            @JsonProperty("respuesta_no_contiene") List<String> replyLacks,
            @JsonProperty("entregado_contiene") List<String> deliveredContains,
            @JsonProperty("entregado_no_contiene") List<String> deliveredLacks,
            @JsonProperty("aviso_al_dueno_contiene") List<String> noticeContains,
            @JsonProperty("aviso_al_dueno_no_contiene") List<String> noticeLacks,
            @JsonProperty("resumen_contiene") List<String> digestContains,
            @JsonProperty("resumen_no_contiene") List<String> digestLacks,
            @JsonProperty("estado") ExpectedState state,
            @JsonProperty("rubrica") String rubric) {
    }

    /** The records after the turn, in the fixed vocabulary; what is not named is not checked. */
    public record ExpectedState(
            @JsonProperty("reservas") Map<String, Integer> bookingCounts,
            @JsonProperty("reserva") Map<String, Object> booking,
            @JsonProperty("casos") Map<String, Integer> caseCounts,
            @JsonProperty("caso") Map<String, Object> handoff,
            @JsonProperty("preguntas") Integer questions,
            @JsonProperty("avisos") Map<String, Integer> noticeCounts,
            @JsonProperty("aviso") Map<String, Object> notice,
            @JsonProperty("resumenes") Integer digests) {
    }
}
