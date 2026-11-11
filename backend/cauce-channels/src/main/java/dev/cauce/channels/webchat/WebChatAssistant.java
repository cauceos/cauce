package dev.cauce.channels.webchat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The shop-assistant mode of a web chat (ADR 0014, WC8): a presence bubble from page load, and an
 * opening of the chat on its own, at most once per session, only on a signal the browser computes on
 * a route where that signal is on, greeting with the signal's template. Nothing here is a model call:
 * the bubble and the templates are this configuration's text, and the model comes in only when the
 * person answers.
 *
 * <p>{@code variants} (at most {@value #MAX_VARIANTS}) each replace the bubble, the templates or the
 * signals in use; the widget assigns one at random and keeps it for the session, and the metrics count
 * by variant.
 *
 * <p>The switches, and their defaults when a configuration leaves them out:
 * <ul>
 *   <li>{@code proactiveOpening}, off: without it the mode is the bubble alone, and no signal opens the
 *       chat. A shop launches with it off and turns it on later (ADR 0014, amendment of 2026-11-11).</li>
 *   <li>{@code proactiveRequiresConsent}, <b>on</b>: the chat opens on its own only after the shop's page
 *       has told the widget the visitor consented. Turned off, the widget opens without that consent
 *       but then keeps nothing in the browser beyond the opened and closed marks.</li>
 *   <li>{@code openingCounter}, off: an opening that ends without a message is counted, anonymously, per
 *       variant and day; with {@code openingCounterRequiresConsent}, off, only after that consent.</li>
 *   <li>{@code purchaseAttribution}, off: a purchase the shop reports is recorded and attributed to the
 *       chat within 7 days of the session's last message. It always needs the visitor's consent: the
 *       widget reports a purchase only once the visitor consented, and says so.</li>
 * </ul>
 * Whether these need consent is the data controller's decision (WC8), within those bounds.
 */
public record WebChatAssistant(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("bubble") String bubble,
        @JsonProperty("card_button_label") String cardButtonLabel,
        @JsonProperty("routes") List<Route> routes,
        @JsonProperty("tuning") Tuning tuning,
        @JsonProperty("templates") Map<String, String> templates,
        @JsonProperty("variants") List<Variant> variants,
        @JsonProperty("proactive_opening") Boolean proactiveOpening,
        @JsonProperty("proactive_requires_consent") Boolean proactiveRequiresConsent,
        @JsonProperty("opening_counter") Boolean openingCounter,
        @JsonProperty("opening_counter_requires_consent") Boolean openingCounterRequiresConsent,
        @JsonProperty("purchase_attribution") Boolean purchaseAttribution) {

    /** The signals the browser computes (WC8). */
    public static final String BACK_TO_PRODUCT = "back_to_product";
    public static final String COMPARING = "comparing";
    public static final String IDLE_CART = "idle_cart";
    public static final String EXIT_INTENT = "exit_intent";
    public static final Set<String> SIGNALS = Set.of(BACK_TO_PRODUCT, COMPARING, IDLE_CART, EXIT_INTENT);

    public static final int MAX_VARIANTS = 5;
    public static final int MAX_ROUTES = 10;
    public static final int MAX_BUBBLE_LENGTH = 120;
    public static final int MAX_TEMPLATE_LENGTH = 300;
    public static final int MAX_PATH_LENGTH = 200;
    private static final Pattern VARIANT_ID = Pattern.compile("[a-z0-9_-]{1,20}");

    /**
     * Where signals are on: a path prefix ({@code /}, {@code /pagar}) and the signals that may open the
     * chat there. The longest prefix that matches the page's path decides.
     */
    public record Route(@JsonProperty("path") String path, @JsonProperty("signals") List<String> signals) {

        public Route {
            if (path == null || !path.startsWith("/") || path.length() > MAX_PATH_LENGTH
                    || path.contains("?") || path.contains("#")) {
                throw new IllegalArgumentException("a route's path is a path prefix starting with '/', without query "
                        + "or fragment, of at most " + MAX_PATH_LENGTH + " characters");
            }
            signals = validSignals(signals, "a route's signals");
        }
    }

    /**
     * How the signals are tuned (WC8's defaults): comparing is {@code comparingViews} product pages within
     * {@code comparingMinutes}; an idle cart is one unchanged for {@code idleCartMinutes}.
     */
    public record Tuning(@JsonProperty("comparing_views") Integer comparingViews,
                         @JsonProperty("comparing_minutes") Integer comparingMinutes,
                         @JsonProperty("idle_cart_minutes") Integer idleCartMinutes) {

        public static final Tuning DEFAULT = new Tuning(3, 5, 3);

        public Tuning {
            comparingViews = bounded(comparingViews, 3, 2, 20, "comparing_views");
            comparingMinutes = bounded(comparingMinutes, 5, 1, 60, "comparing_minutes");
            idleCartMinutes = bounded(idleCartMinutes, 3, 1, 60, "idle_cart_minutes");
        }
    }

    /** One variant: what it replaces of the configuration's bubble, templates and signals in use. */
    public record Variant(@JsonProperty("id") String id, @JsonProperty("bubble") String bubble,
                          @JsonProperty("templates") Map<String, String> templates,
                          @JsonProperty("signals") List<String> signals) {

        public Variant {
            if (id == null || !VARIANT_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("a variant's id is 1 to 20 lowercase letters, digits, '-' or '_'");
            }
            bubble = optional(bubble, "a variant's bubble", MAX_BUBBLE_LENGTH);
            templates = templates == null ? null : validTemplates(templates);
            signals = signals == null ? null : validSignals(signals, "a variant's signals");
        }
    }

    public WebChatAssistant {
        proactiveOpening = proactiveOpening != null && proactiveOpening;
        proactiveRequiresConsent = proactiveRequiresConsent == null || proactiveRequiresConsent;
        openingCounter = openingCounter != null && openingCounter;
        openingCounterRequiresConsent = openingCounterRequiresConsent != null && openingCounterRequiresConsent;
        purchaseAttribution = purchaseAttribution != null && purchaseAttribution;
        bubble = optional(bubble, "bubble", MAX_BUBBLE_LENGTH);
        cardButtonLabel = optional(cardButtonLabel, "card_button_label", 30);
        routes = routes == null ? List.of() : List.copyOf(routes);
        if (routes.size() > MAX_ROUTES) {
            throw new IllegalArgumentException("routes holds at most " + MAX_ROUTES + " routes");
        }
        if (routes.stream().map(Route::path).distinct().count() != routes.size()) {
            throw new IllegalArgumentException("each route's path is different");
        }
        tuning = tuning == null ? Tuning.DEFAULT : tuning;
        templates = validTemplates(templates);
        variants = variants == null ? List.of() : List.copyOf(variants);
        if (variants.size() > MAX_VARIANTS) {
            throw new IllegalArgumentException("variants holds at most " + MAX_VARIANTS + " variants");
        }
        Set<String> ids = new HashSet<>();
        for (Variant variant : variants) {
            if (!ids.add(variant.id())) {
                throw new IllegalArgumentException("variant '" + variant.id() + "' is declared twice");
            }
        }
    }

    /** The variant called {@code id}, if this configuration declares it. */
    public Optional<Variant> variant(String id) {
        return id == null ? Optional.empty() : variants.stream().filter(v -> v.id().equals(id)).findFirst();
    }

    /**
     * The greeting the widget showed when it opened on {@code signal} for {@code variantId}: the variant's
     * template for that signal, else the configuration's. What reaches the agent is resolved here, from
     * this configuration, never taken from the browser. Nothing while {@code proactiveOpening} is off: then
     * no signal opens the chat, so no greeting was shown.
     */
    public Optional<String> greetingFor(String signal, String variantId) {
        if (!proactiveOpening || signal == null || !SIGNALS.contains(signal)) {
            return Optional.empty();
        }
        Optional<String> ofVariant = variant(variantId).map(Variant::templates).map(t -> t.get(signal));
        return ofVariant.isPresent() ? ofVariant : Optional.ofNullable(templates.get(signal));
    }

    private static List<String> validSignals(List<String> signals, String field) {
        List<String> copy = signals == null ? List.of() : List.copyOf(signals);
        for (String signal : copy) {
            if (!SIGNALS.contains(signal)) {
                throw new IllegalArgumentException(field + " are among " + SIGNALS + ", not '" + signal + "'");
            }
        }
        return copy;
    }

    private static Map<String, String> validTemplates(Map<String, String> templates) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (templates != null) {
            templates.forEach((signal, text) -> {
                if (!SIGNALS.contains(signal)) {
                    throw new IllegalArgumentException("templates are keyed by signal, among " + SIGNALS
                            + ", not '" + signal + "'");
                }
                String value = optional(text, "the template of " + signal, MAX_TEMPLATE_LENGTH);
                if (value != null) {
                    copy.put(signal, value);
                }
            });
        }
        return Map.copyOf(copy);
    }

    private static Integer bounded(Integer value, int fallback, int min, int max, String field) {
        int chosen = value == null ? fallback : value;
        if (chosen < min || chosen > max) {
            throw new IllegalArgumentException(field + " is between " + min + " and " + max);
        }
        return chosen;
    }

    private static String optional(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return stripped;
    }
}
