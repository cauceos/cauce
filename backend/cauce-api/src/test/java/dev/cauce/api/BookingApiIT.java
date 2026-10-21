package dev.cauce.api;

import static dev.cauce.api.support.FakeLlmProvider.finalReply;
import static dev.cauce.api.support.FakeLlmProvider.lastToolOutput;
import static dev.cauce.api.support.FakeLlmProvider.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bookings end to end (ADR 0007, B6): the agenda over the API, and an agent taking a booking
 * through its tools with a scripted model. The two-turn rule is enforced by the code, whatever the
 * model does: a model that proposes and confirms in one turn gets the confirmation refused, and
 * only the person's next message can confirm it.
 */
class BookingApiIT extends AbstractBusinessApiIT {

    private static final String AGENDA = """
            {"unit": "comensales", "max_party_size": 8, "hold_minutes": 10,
             "shifts": [{"id": "cena", "days": ["mon","tue","wed","thu","fri","sat","sun"],
                         "start": "20:30", "end": "23:00", "capacity": 10}],
             "closed_dates": ["2026-12-25"]}""";

    private static final List<String> BOOKING_TOOLS = List.of("check_availability", "propose_booking",
            "propose_booking_change", "propose_cancellation", "confirm_booking", "list_my_bookings");

    private LocalDate nextTuesday;

    @BeforeEach
    void loadAgenda() throws Exception {
        putJson(clientAuth, "/v1/tenants/" + clientId + "/business-profile",
                "{\"name\": \"Bar\", \"time_zone\": \"Europe/Madrid\", \"facts\": {}}").andExpect(status().isOk());
        putJson(clientAuth, "/v1/tenants/" + clientId + "/agenda", AGENDA).andExpect(status().isOk());
        nextTuesday = LocalDate.now(clock.withZone(ZoneId.of("Europe/Madrid"))).plusDays(1);
        while (nextTuesday.getDayOfWeek() != DayOfWeek.TUESDAY) {
            nextTuesday = nextTuesday.plusDays(1);
        }
    }

    @Test
    void agenda_isReadBack_validated_andScopedToTheHierarchy() throws Exception {
        getAs(partnerAuth, "/v1/tenants/" + clientId + "/agenda")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hold_minutes").value(10))
                .andExpect(jsonPath("$.horizon_days").value(60))
                .andExpect(jsonPath("$.shifts[0].days.length()").value(7))
                .andExpect(jsonPath("$.closed_dates[0]").value("2026-12-25"));
        getAs(otherClientAuth, "/v1/tenants/" + clientId + "/agenda")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("agenda_not_found"));
        putJson(clientAuth, "/v1/tenants/" + clientId + "/agenda", AGENDA.replace("\"mon\"", "\"lunes\""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aBooking_proposedAndConfirmedInOneTurn_isOnlyConfirmedByThePersonsNextMessage() throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, BOOKING_TOOLS);
        String date = nextTuesday.toString();
        llm.script(
                toolCall("check_availability", Map.of("date", date)),
                toolCall("propose_booking", Map.of("date", date, "shift", "cena", "party_size", 4,
                        "contact_name", "Ana López", "contact_phone", "+34 600 000 000")));
        // A model that does not wait: it confirms the code it was just given, in the same turn.
        llm.scriptReacting(invocation -> toolCall("confirm_booking", Map.of("code", codeIn(lastToolOutput(invocation)))));
        llm.script(finalReply("Le propongo mesa para 4 el martes a las 20:30 a nombre de Ana López. ¿Confirmo?"));

        UUID conversationId = sendAndProcess(agentId, "cliente-1", "Mesa para 4 el martes para cenar, soy Ana");

        List<String> outputs = toolOutputs(conversationId);
        assertThat(outputs.get(0)).contains("\"remaining\":10").contains("\"bookable\":true");
        String code = codeIn(outputs.get(1));
        assertThat(outputs.get(1)).contains("\"status\":\"HELD\"").contains("\"start\":\"20:30\"");
        assertThat(outputs.get(2)).contains("\"reason\":\"SAME_TURN\"");
        assertThat(bookingStatus(code)).isEqualTo("HELD");

        llm.script(toolCall("confirm_booking", Map.of("code", code)), finalReply("Reserva confirmada."));
        sendAndProcess(agentId, "cliente-1", "Sí, confirmo");

        assertThat(bookingStatus(code)).isEqualTo("CONFIRMED");
        getAs(partnerAuth, "/v1/tenants/" + clientId + "/bookings?date=" + date)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].contact_name").value("Ana López"))
                .andExpect(jsonPath("$.data[0].party_size").value(4));
    }

    @Test
    void aConfirmationInTheProposalsTurn_isRefusedAsSameTurn() throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, BOOKING_TOOLS);
        String date = nextTuesday.toString();
        llm.script(toolCall("propose_booking", Map.of("date", date, "shift", "cena", "party_size", 2,
                "contact_name", "Luis")), finalReply("¿Confirmo?"));
        UUID conversationId = sendAndProcess(agentId, "cliente-1", "Mesa para 2 el martes");
        String code = codeIn(toolOutputs(conversationId).get(0));

        // The person changes the subject; the model confirms anyway, then in the same turn tries a
        // cancellation request and its confirmation together.
        llm.script(toolCall("confirm_booking", Map.of("code", code)),
                toolCall("propose_cancellation", Map.of("code", code)),
                toolCall("confirm_booking", Map.of("code", code)),
                finalReply("Hecho."));
        sendAndProcess(agentId, "cliente-1", "Vale. Y si al final no puedo ir, cancela");

        List<String> outputs = toolOutputs(conversationId);
        assertThat(outputs.get(1)).contains("\"status\":\"CONFIRMED\"");
        assertThat(outputs.get(2)).contains("\"waiting_for\":\"cancellation\"");
        assertThat(outputs.get(3)).contains("\"reason\":\"SAME_TURN\"");
        assertThat(bookingStatus(code)).isEqualTo("CONFIRMED");
    }

    @Test
    void aPersonCannotTouchSomeoneElsesBooking_byItsCode() throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, BOOKING_TOOLS);
        llm.script(toolCall("propose_booking", Map.of("date", nextTuesday.toString(), "shift", "cena",
                "party_size", 2, "contact_name", "Luis")), finalReply("¿Confirmo?"));
        UUID conversationId = sendAndProcess(agentId, "cliente-1", "Mesa para 2 el martes");
        String code = codeIn(toolOutputs(conversationId).get(0));

        llm.script(toolCall("confirm_booking", Map.of("code", code)), toolCall("list_my_bookings", Map.of()),
                finalReply("No encuentro esa reserva."));
        UUID strangerConversation = sendAndProcess(agentId, "cliente-2", "Confirma la reserva " + code);

        List<String> outputs = toolOutputs(strangerConversation);
        assertThat(outputs.get(0)).contains("\"reason\":\"NOT_FOUND\"");
        assertThat(outputs.get(1)).isEqualTo("{\"bookings\":[],\"ok\":true}");
        assertThat(bookingStatus(code)).isEqualTo("HELD");
    }

    private String codeIn(String toolOutput) {
        try {
            return objectMapper.readTree(toolOutput).at("/booking/code").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String bookingStatus(String code) throws Exception {
        JsonNode page = json(getAs(clientAuth, "/v1/tenants/" + clientId + "/bookings").andExpect(status().isOk()));
        for (JsonNode booking : page.get("data")) {
            if (code.equals(booking.get("code").asText())) {
                return booking.get("status").asText();
            }
        }
        throw new AssertionError("no booking " + code);
    }
}
