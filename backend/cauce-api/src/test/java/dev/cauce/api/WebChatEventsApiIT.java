package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cauce.channels.webchat.TurnstileVerifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What the shop's page reports to the chat, of ADR 0014, WC8, end to end: an add to cart from a card,
 * a purchase recorded only when the channel attributes purchases and the visitor consented, attributed
 * within 7 days of the session's last message and recorded once per order,
 * and the anonymous opening counter, which counts only when the channel turns it on and keeps nothing
 * but aggregates.
 */
class WebChatEventsApiIT extends AbstractBusinessApiIT {

    private static final String SHOP = "https://caucewebs.es";

    @MockitoBean
    private TurnstileVerifier turnstile;

    @Autowired
    private MeterRegistry meters;

    private JdbcTemplate admin;
    private String key;
    private String channelId;
    private String token;

    @BeforeEach
    void channelSessionAndCatalog() throws Exception {
        admin = new JdbcTemplate(adminDataSource);
        when(turnstile.verify(eq("0x4AAAAAAA-secreto"), eq("token-bueno"))).thenReturn(true);
        postJson(bearerFor(operatorId), "/v1/llm-prices", """
                {"provider": "anthropic", "model": "claude-sonnet-4-7", "input_usd_per_mtok": 2.00,
                 "output_usd_per_mtok": 10.00}
                """).andExpect(status().isCreated());
        putJson(partnerAuth, "/v1/tenants/" + clientId + "/spend-limit", "{\"monthly_limit_usd\": 50}")
                .andExpect(status().isOk());
        putJson(clientAuth, "/v1/tenants/" + clientId + "/catalog", """
                {"declared_attributes": [], "items": [{"ref": "plan-plus", "section": "Webs", "name": "Plan Plus",
                  "price": 79}]}
                """).andExpect(status().isOk());
        JsonNode channel = json(postJson(clientAuth, "/v1/agents/" + createAgent(clientId, clientAuth, List.of())
                + "/web-chat-channels", """
                {"turnstile_secret": "0x4AAAAAAA-secreto", "turnstile_site_key": "0x4AAAAAAA-sitio",
                 "allowed_origins": ["%s"], "privacy_policy_url": "https://caucewebs.es/privacidad"}
                """.formatted(SHOP)).andExpect(status().isCreated()));
        key = channel.get("public_key").asText();
        channelId = channel.get("id").asText();
        mockMvc.perform(put("/v1/web-chat-channels/" + channelId + "/assistant").header(HttpHeaders.AUTHORIZATION, clientAuth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": true, \"variants\": [{\"id\": \"a\"}]}"))
                .andExpect(status().isOk());
        token = json(mockMvc.perform(post("/web/v1/" + key + "/sessions").header(HttpHeaders.ORIGIN, SHOP)
                .contentType(MediaType.APPLICATION_JSON).content("{\"turnstile_token\": \"token-bueno\"}"))
                .andExpect(status().isCreated())).get("session_token").asText();
    }

    @Test
    void anAddToCart_fromACard_isRecordedForACatalogRef_only() throws Exception {
        event(token, "{\"type\": \"add_to_cart\", \"ref\": \"plan-plus\", \"variant\": \"a\"}")
                .andExpect(status().isNoContent());
        event(token, "{\"type\": \"add_to_cart\", \"ref\": \"plan-oro\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_message"));
        event(token, "{\"type\": \"vista\"}").andExpect(status().isBadRequest());
        event(null, "{\"type\": \"add_to_cart\", \"ref\": \"plan-plus\"}").andExpect(status().isUnauthorized());

        assertThat(admin.queryForObject("SELECT kind || ':' || ref || ':' || quantity || ':' || variant FROM web_events",
                String.class)).isEqualTo("ADD_TO_CART:plan-plus:1:a");
        assertThat(meters.counter("cauce.web.cart_adds", "channel_config", channelId, "variant", "a").count())
                .isEqualTo(1.0);
    }

    @Test
    void aPurchase_isIgnored_unlessTheChannelAttributesPurchases_andTheVisitorConsented() throws Exception {
        send("Quiero el Plus");
        // Attribution is off by default: the purchase is checked, then recorded nowhere.
        event(token, purchase("CW-1", "79.00")).andExpect(jsonPath("$.recorded").value(false))
                .andExpect(jsonPath("$.attributed").value(false));
        event(token, purchase("CW-1", "-1")).andExpect(status().isBadRequest());

        attributePurchases();
        // On, it still needs the visitor's consent, which the widget says.
        event(token, purchase("CW-2", "79.00").replace(", \"consent\": true", ""))
                .andExpect(jsonPath("$.recorded").value(false));
        event(token, purchase("CW-3", "79.00").replace("\"consent\": true", "\"consent\": false"))
                .andExpect(jsonPath("$.recorded").value(false));
        event(token, purchase("CW-4", "79.00")).andExpect(jsonPath("$.recorded").value(true))
                .andExpect(jsonPath("$.attributed").value(true));

        assertThat(admin.queryForObject("SELECT string_agg(order_id, ',') FROM web_events", String.class))
                .isEqualTo("CW-4");
        assertThat(meters.counter("cauce.web.purchases", "channel_config", channelId, "variant", "a").count())
                .isEqualTo(1.0);
    }

    @Test
    void aPurchase_isAttributedWithinSevenDaysOfTheLastMessage_andRecordedOncePerOrder() throws Exception {
        attributePurchases();
        event(token, purchase("CW-1", "79.00")).andExpect(jsonPath("$.recorded").value(true))
                .andExpect(jsonPath("$.attributed").value(false));

        send("Quiero el Plus");
        event(token, purchase("CW-2", "95.59")).andExpect(jsonPath("$.recorded").value(true))
                .andExpect(jsonPath("$.attributed").value(true));
        event(token, purchase("CW-2", "95.59")).andExpect(jsonPath("$.recorded").value(false));
        assertThat(meters.counter("cauce.web.purchases", "channel_config", channelId, "variant", "a").count())
                .isEqualTo(1.0);
        assertThat(meters.summary("cauce.web.purchase_value", "channel_config", channelId, "variant", "a").totalAmount())
                .isEqualTo(95.59);

        // Eight days after the last message, the chat no longer takes the credit.
        admin.update("UPDATE messages SET created_at = created_at - interval '8 days'");
        event(token, purchase("CW-3", "79")).andExpect(jsonPath("$.attributed").value(false));
        event(token, purchase("CW-4", "-1")).andExpect(status().isBadRequest());
        event(token, purchase("CW-5", "1.999")).andExpect(status().isBadRequest());
        assertThat(admin.queryForObject("SELECT string_agg(order_id || ':' || attributed, ',' ORDER BY order_id) "
                + "FROM web_events", String.class)).isEqualTo("CW-1:false,CW-2:true,CW-3:false");
    }

    @Test
    void theAnonymousOpeningCounter_countsOnlyWhenTheChannelTurnsItOn_andKeepsOnlyAggregates() throws Exception {
        opening("{\"kind\": \"proactive\", \"signal\": \"idle_cart\", \"variant\": \"a\"}").andExpect(status().isNoContent());
        assertThat(admin.queryForObject("SELECT count(*) FROM web_opening_counts", Integer.class)).isZero();

        mockMvc.perform(put("/v1/web-chat-channels/" + channelId + "/assistant").header(HttpHeaders.AUTHORIZATION, clientAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\": true, \"opening_counter\": true, \"variants\": [{\"id\": \"a\"}]}"))
                .andExpect(status().isOk());
        opening("{\"kind\": \"proactive\", \"signal\": \"idle_cart\", \"variant\": \"a\"}").andExpect(status().isNoContent());
        opening("{\"kind\": \"proactive\", \"signal\": \"idle_cart\", \"variant\": \"a\"}").andExpect(status().isNoContent());
        opening("{\"kind\": \"on_demand\", \"signal\": \"idle_cart\", \"variant\": \"x\"}").andExpect(status().isNoContent());
        opening("{\"kind\": \"siempre\"}").andExpect(status().isBadRequest());

        assertThat(admin.queryForObject("SELECT string_agg(variant || ':' || kind || ':' || signal || ':' || openings, ',' "
                + "ORDER BY kind) FROM web_opening_counts", String.class))
                .isEqualTo("none:on_demand:none:1,a:proactive:idle_cart:2");
        assertThat(meters.counter("cauce.web.openings_counted", "channel_config", channelId, "variant", "a", "kind",
                "proactive", "signal", "idle_cart").count()).isEqualTo(2.0);
    }

    private void send(String content) throws Exception {
        mockMvc.perform(post("/web/v1/" + key + "/messages").header(HttpHeaders.ORIGIN, SHOP)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"" + content + "\"}"))
                .andExpect(status().isAccepted());
    }

    private void attributePurchases() throws Exception {
        mockMvc.perform(put("/v1/web-chat-channels/" + channelId + "/assistant").header(HttpHeaders.AUTHORIZATION, clientAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\": true, \"purchase_attribution\": true, \"variants\": [{\"id\": \"a\"}]}"))
                .andExpect(status().isOk());
    }

    /** A purchase as the widget reports it once the visitor consented. */
    private static String purchase(String orderId, String value) {
        return "{\"type\": \"purchase\", \"order_id\": \"" + orderId + "\", \"value\": " + value
                + ", \"variant\": \"a\", \"consent\": true}";
    }

    private ResultActions event(String sessionToken, String json) throws Exception {
        var request = post("/web/v1/" + key + "/events").header(HttpHeaders.ORIGIN, SHOP)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (sessionToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken);
        }
        return mockMvc.perform(request);
    }

    private ResultActions opening(String json) throws Exception {
        return mockMvc.perform(post("/web/v1/" + key + "/openings").header(HttpHeaders.ORIGIN, SHOP)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
