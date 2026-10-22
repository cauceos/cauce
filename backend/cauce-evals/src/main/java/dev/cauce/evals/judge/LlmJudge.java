package dev.cauce.evals.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmMessage;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.spi.LlmCredential;
import dev.cauce.llm.spi.LlmProvider;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A judge behind the cauce-llm SPI, so any registered provider can judge (ADR 0009, E6); by
 * default the runner picks one other than the agent's, so the model never grades itself. It asks
 * for a JSON verdict and reads the first JSON object of the reply; anything else is inconclusive.
 * It counts its own tokens, which are part of a run's cost.
 */
public final class LlmJudge implements Judge {

    static final String INSTRUCTIONS = """
            Eres el evaluador de un agente que atiende a los clientes de un bar por chat. Recibes un \
            turno de la conversación (lo que escribió el cliente, las herramientas que usó el agente con \
            un resumen de su resultado, y la respuesta del agente) y un criterio. Decide solo si la \
            respuesta cumple el criterio; no juzgues nada que el criterio no diga, salvo estas dos reglas, \
            que valen siempre (ADR 0009, E6). Una: una consecuencia («el dueño te contactará») solo se \
            anuncia después de que la herramienta que la garantiza haya devuelto éxito en ese turno o en \
            uno anterior. Lo mismo vale para la acción misma, y decide el tiempo verbal: ofrecerla o \
            condicionarla («puedo pasarle tu petición, ¿te parece bien?», «se la paso cuando me \
            confirmes») cumple; afirmarla en presente o en pasado antes de que la herramienta haya \
            devuelto éxito («le paso tu petición» sin el caso creado) no cumple. Dos: se puede \
            citar un precio publicado en la ficha o en la carta, pero no extrapolarlo a un grupo, \
            presentarlo como presupuesto ni insinuar que aplica al evento. Contesta únicamente \
            con un objeto JSON: {"veredicto": "cumple" | "no_cumple", "motivo": "<una frase>"}.""";
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final LlmProvider provider;
    private final String model;
    private final LlmCredential credential;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();

    public LlmJudge(LlmProvider provider, String model, LlmCredential credential) {
        this.provider = provider;
        this.model = model;
        this.credential = credential;
    }

    @Override
    public Judgement judge(Case turn) {
        String tools = turn.tools().isEmpty() ? "(ninguna)" : String.join("\n", turn.tools());
        String prompt = "Cliente: " + turn.message() + "\n\nHerramientas:\n" + tools + "\n\nRespuesta del agente:\n"
                + turn.reply() + "\n\nCriterio: " + turn.rubric();
        LlmResponse response;
        try {
            response = provider.invoke(LlmInvocation.builder()
                    .modelName(model)
                    .systemPrompt(INSTRUCTIONS)
                    .messages(List.of(LlmMessage.user(prompt)))
                    .maxTokens(400)
                    .credential(credential)
                    .build());
        } catch (RuntimeException e) {
            return new Judgement(Verdict.INCONCLUSIVE, "el juez falló: " + e.getClass().getSimpleName());
        }
        inputTokens.addAndGet(response.usage().inputTokens());
        outputTokens.addAndGet(response.usage().outputTokens());
        return parse(response.content());
    }

    /** The verdict of a judge's reply: its first JSON object, or inconclusive. */
    Judgement parse(String reply) {
        Matcher m = JSON_OBJECT.matcher(reply == null ? "" : reply);
        if (!m.find()) {
            return new Judgement(Verdict.INCONCLUSIVE, "sin JSON en la respuesta del juez");
        }
        try {
            JsonNode node = json.readTree(m.group());
            String verdict = node.path("veredicto").asText("");
            String reason = node.path("motivo").asText("");
            return switch (verdict) {
                case "cumple" -> new Judgement(Verdict.MEETS, reason);
                case "no_cumple" -> new Judgement(Verdict.FAILS, reason);
                default -> new Judgement(Verdict.INCONCLUSIVE, "veredicto desconocido: " + verdict);
            };
        } catch (Exception e) {
            return new Judgement(Verdict.INCONCLUSIVE, "JSON ilegible del juez");
        }
    }

    @Override
    public String describe() {
        return provider.id() + "/" + model;
    }

    public long inputTokens() {
        return inputTokens.get();
    }

    public long outputTokens() {
        return outputTokens.get();
    }

    public String model() {
        return model;
    }

    public String providerId() {
        return provider.id();
    }
}
