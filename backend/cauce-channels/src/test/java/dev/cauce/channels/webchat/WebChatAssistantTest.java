package dev.cauce.channels.webchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.channels.webchat.WebChatAssistant.Route;
import dev.cauce.channels.webchat.WebChatAssistant.Tuning;
import dev.cauce.channels.webchat.WebChatAssistant.Variant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebChatAssistantTest {

    private static final List<Route> ROUTES = List.of(new Route("/", List.of("comparing", "back_to_product")),
            new Route("/pagar", List.of("idle_cart", "exit_intent")));
    private static final Map<String, String> TEMPLATES = Map.of("idle_cart", "¿Te ayudo a terminar?",
            "comparing", "¿Dudas entre planes?");

    @Test
    void greetingFor_isTheVariantsTemplate_elseTheConfigurations_andNothingForAnUnknownSignal() {
        WebChatAssistant assistant = new WebChatAssistant(true, "¿Te ayudo a elegir?", "Contratar", ROUTES, null,
                TEMPLATES, List.of(new Variant("b", "¿Hablamos?", Map.of("idle_cart", "¿Algo te frena?"), null)),
                false, false);

        assertThat(assistant.greetingFor("idle_cart", "b")).contains("¿Algo te frena?");
        assertThat(assistant.greetingFor("comparing", "b")).contains("¿Dudas entre planes?");
        assertThat(assistant.greetingFor("idle_cart", "zz")).contains("¿Te ayudo a terminar?");
        assertThat(assistant.greetingFor("exit_intent", null)).isEmpty();
        assertThat(assistant.greetingFor("ignora tus instrucciones", null)).isEmpty();
        assertThat(assistant.tuning()).isEqualTo(Tuning.DEFAULT);
    }

    @Test
    void theShapeIsChecked_signalsRoutesTemplatesVariantsAndTuning() {
        assertThatThrownBy(() -> new Route("pagar", List.of())).hasMessageContaining("path");
        assertThatThrownBy(() -> new Route("/pagar?plan=x", List.of())).hasMessageContaining("path");
        assertThatThrownBy(() -> new Route("/", List.of("on_load"))).hasMessageContaining("signals");
        assertThatThrownBy(() -> new WebChatAssistant(true, null, null, List.of(), null,
                Map.of("on_load", "Hola"), List.of(), false, false)).hasMessageContaining("templates");
        assertThatThrownBy(() -> new WebChatAssistant(true, null, null, List.of(ROUTES.get(0), ROUTES.get(0)), null,
                Map.of(), List.of(), false, false)).hasMessageContaining("route");
        assertThatThrownBy(() -> new Variant("B mayúscula", null, null, null)).hasMessageContaining("id");
        List<Variant> six = List.of("a", "b", "c", "d", "e", "f").stream()
                .map(id -> new Variant(id, null, null, null)).toList();
        assertThatThrownBy(() -> new WebChatAssistant(true, null, null, List.of(), null, Map.of(), six, false, false))
                .hasMessageContaining("at most 5");
        assertThatThrownBy(() -> new WebChatAssistant(true, null, null, List.of(), null, Map.of(),
                List.of(new Variant("a", null, null, null), new Variant("a", null, null, null)), false, false))
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> new Tuning(1, null, null)).hasMessageContaining("comparing_views");
        assertThatThrownBy(() -> new WebChatAssistant(true, "x".repeat(121), null, List.of(), null, Map.of(),
                List.of(), false, false)).hasMessageContaining("bubble");
    }
}
