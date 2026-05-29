package dev.cauce.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests for {@link RequestIdFilter}: every response carries an {@code X-Request-Id}
 * header, the id is unique per request, and the header matches the {@code request_id} the
 * handler reads from the MDC (proving the same value flows from filter to body).
 */
class RequestIdFilterIT extends AbstractApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void everyResponse_carriesRequestIdHeader() throws Exception {
        String id = mockMvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER);

        assertThat(id).isNotBlank();
    }

    @Test
    void distinctRequests_getDistinctRequestIds() throws Exception {
        String first = mockMvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        String second = mockMvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER);

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void headerRequestId_matchesBodyRequestId() throws Exception {
        // An unauthenticated call to a protected endpoint produces an ErrorResponse body whose
        // request_id is read from the MDC. It must equal the header set by the same filter run.
        var response = mockMvc.perform(get("/test/throw/not-found"))
                .andReturn().getResponse();

        String header = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        ErrorResponse body = objectMapper.readValue(response.getContentAsString(), ErrorResponse.class);

        assertThat(header).isNotBlank();
        assertThat(body.requestId()).isEqualTo(header);
    }
}
