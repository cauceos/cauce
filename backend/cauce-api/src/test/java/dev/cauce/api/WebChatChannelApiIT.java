package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The web-chat channel of ADR 0014, W1, end to end: created for an agent only when its tenant has a
 * spend limit, reached anonymously by its public key from an allowed origin, closed to every other
 * origin and key alike, and holding the tenant's limit in place while it is active.
 */
class WebChatChannelApiIT extends AbstractBusinessApiIT {

    private static final String SHOP = "https://tienda.example.com";

    private static final String CREATE = """
            {"turnstile_secret": "0x4AAAAAAA-secreto", "turnstile_site_key": "0x4AAAAAAA-sitio",
             "allowed_origins": ["https://Tienda.example.com/", "http://localhost:4321"],
             "privacy_policy_url": "https://tienda.example.com/privacidad"}
            """;

    @Test
    void aWebChat_needsASpendLimit_thenHasAPublicKey_andNeverShowsOrStoresItsSecretInClear() throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, List.of());

        postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", CREATE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("spend_limit_required"));
        setLimit();

        String body = postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", CREATE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel_type").value("web_chat"))
                .andExpect(jsonPath("$.public_key").value(org.hamcrest.Matchers.matchesPattern("pk_[A-Za-z0-9]{24}")))
                .andExpect(jsonPath("$.allowed_origins[0]").value(SHOP))
                .andExpect(jsonPath("$.allowed_origins[1]").value("http://localhost:4321"))
                .andExpect(jsonPath("$.consent_mode").value("NOTICE"))
                .andExpect(jsonPath("$.turnstile_secret").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("secreto");
        String id = objectMapper.readTree(body).get("id").asText();
        String stored = new JdbcTemplate(adminDataSource).queryForObject(
                "SELECT credential FROM channel_configs WHERE id = ?::uuid", String.class, id);
        assertThat(stored).doesNotContain("secreto");

        getAs(partnerAuth, "/v1/web-chat-channels/" + id).andExpect(status().isOk());
        getAs(otherClientAuth, "/v1/web-chat-channels/" + id).andExpect(status().isNotFound());
    }

    @Test
    void aMalformedOrigin_orAPrivacyPolicyOverHttp_isABadRequest() throws Exception {
        setLimit();
        UUID agentId = createAgent(clientId, clientAuth, List.of());

        postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", CREATE.replace(
                "\"https://Tienda.example.com/\"", "\"https://tienda.example.com/tienda\""))
                .andExpect(status().isBadRequest());
        postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", CREATE.replace(
                "https://tienda.example.com/privacidad", "http://tienda.example.com/privacidad"))
                .andExpect(status().isBadRequest());
        postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", CREATE.replace(
                "\"https://Tienda.example.com/\", \"http://localhost:4321\"", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theConfig_isReadAnonymously_fromAnAllowedOrigin_andRefusedAlikeToAnyOtherOriginOrKey() throws Exception {
        String publicKey = createChannel();

        mockMvc.perform(get("/web/v1/" + publicKey + "/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SHOP))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("max-age=300")))
                .andExpect(jsonPath("$.public_key").value(publicKey))
                .andExpect(jsonPath("$.turnstile_site_key").value("0x4AAAAAAA-sitio"))
                .andExpect(jsonPath("$.privacy_policy_url").value("https://tienda.example.com/privacidad"))
                .andExpect(jsonPath("$.consent_required").value(false))
                .andExpect(jsonPath("$.turnstile_secret").doesNotExist());
        // Not a browser: no CORS to check.
        mockMvc.perform(get("/web/v1/" + publicKey + "/config")).andExpect(status().isOk());

        mockMvc.perform(get("/web/v1/" + publicKey + "/config").header(HttpHeaders.ORIGIN, "https://otra.example.com"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(get("/web/v1/pk_AAAAAAAAAAAAAAAAAAAAAAAA/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(get("/web/v1/no-es-una-clave/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPreflight_fromAnAllowedOrigin_isAnsweredWithTheSurfacesMethodsAndHeaders_andNoCredentials() throws Exception {
        String publicKey = createChannel();

        mockMvc.perform(options("/web/v1/" + publicKey + "/sessions").header(HttpHeaders.ORIGIN, SHOP)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SHOP))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        org.hamcrest.Matchers.containsString("Authorization")))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        mockMvc.perform(options("/web/v1/" + publicKey + "/sessions").header(HttpHeaders.ORIGIN, "https://otra.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void whileAWebChatIsActive_theTenantsSpendLimitCannotBeRemoved() throws Exception {
        createChannel();

        mockMvc.perform(delete("/v1/tenants/" + clientId + "/spend-limit").header(HttpHeaders.AUTHORIZATION, partnerAuth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("spend_limit_required"));
        getAs(clientAuth, "/v1/tenants/" + clientId + "/spend-limit")
                .andExpect(jsonPath("$.monthly_limit_usd").value(50.0));
    }

    private void setLimit() throws Exception {
        putJson(partnerAuth, "/v1/tenants/" + clientId + "/spend-limit", """
                {"monthly_limit_usd": 50}
                """).andExpect(status().isOk());
    }

    private String createChannel() throws Exception {
        setLimit();
        UUID agentId = createAgent(clientId, clientAuth, List.of());
        return json(postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", CREATE)
                .andExpect(status().isCreated())).get("public_key").asText();
    }
}
