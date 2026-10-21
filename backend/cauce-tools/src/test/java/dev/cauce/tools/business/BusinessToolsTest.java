package dev.cauce.tools.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.core.business.catalog.Catalog;
import dev.cauce.core.business.catalog.CatalogItem;
import dev.cauce.core.business.catalog.CatalogSource;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.gap.KnowledgeGap;
import dev.cauce.core.business.gap.KnowledgeGapSink;
import dev.cauce.core.business.handoff.Handoff;
import dev.cauce.core.business.handoff.HandoffSink;
import dev.cauce.core.business.handoff.InvalidHandoffCategoryException;
import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfile.Fact;
import dev.cauce.core.business.profile.BusinessProfileSource;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.tools.spi.ToolArguments.InvalidToolArgumentException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BusinessToolsTest {

    private final UUID tenantId = UUID.randomUUID();
    private final ToolExecutionContext context = new ToolExecutionContext(tenantId, UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    private final Catalog catalog = Catalog.create(tenantId, List.of("allergens"), List.of(
            new CatalogItem("bravas", "Tapas", "Patatas bravas", null, new BigDecimal("5.50"),
                    Map.of("allergens", List.of("huevo"))),
            new CatalogItem("tarta-queso", "Postres", "Tarta de queso", null, new BigDecimal("5.00"), Map.of())));

    @Test
    void getBusinessInfo_returnsTheStatedFactsOfTheContextTenant() {
        BusinessProfileSource source = mock(BusinessProfileSource.class);
        when(source.findProfile(tenantId)).thenReturn(Optional.of(BusinessProfile.create(tenantId, "Bar La Esquina",
                "Europe/Madrid", "es", List.of(new Fact("terraza", "sí, 6 mesas")))));

        ToolResult result = new GetBusinessInfoTool(() -> source).execute(call("get_business_info", Map.of()), context);

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("\"name\":\"Bar La Esquina\"").contains("\"terraza\":\"sí, 6 mesas\"");
    }

    @Test
    void getBusinessInfo_withoutAProfile_saysNothingIsKnown() {
        BusinessProfileSource source = mock(BusinessProfileSource.class);
        when(source.findProfile(tenantId)).thenReturn(Optional.empty());

        assertThat(new GetBusinessInfoTool(() -> source).execute(call("get_business_info", Map.of()), context).output())
                .contains("\"available\":false");
        assertThat(new GetBusinessInfoTool(() -> null).execute(call("get_business_info", Map.of()), context).output())
                .contains("\"available\":false");
    }

    @Test
    void searchCatalog_reportsADeclaredAttributeTheItemDoesNotStateAsNotDeclared() {
        CatalogSource source = mock(CatalogSource.class);
        when(source.findCatalog(tenantId)).thenReturn(Optional.of(catalog));

        String output = new SearchCatalogTool(() -> source)
                .execute(call("search_catalog", Map.of("query", "tarta de queso")), context).output();

        assertThat(output).contains("\"name\":\"Tarta de queso\"").contains("\"allergens\":\"NOT_DECLARED\"")
                .doesNotContain("bravas");
    }

    @Test
    void searchCatalog_withoutAValue_setsAsideWhatItCannotVouchFor() {
        CatalogSource source = mock(CatalogSource.class);
        when(source.findCatalog(tenantId)).thenReturn(Optional.of(catalog));

        String output = new SearchCatalogTool(() -> source).execute(call("search_catalog",
                Map.of("without_attribute", "allergens", "without_value", "gluten")), context).output();

        assertThat(output).contains("\"items\":[{\"attributes\":{\"allergens\":[\"huevo\"]}")
                .contains("\"undetermined\":[{\"attributes\":{\"allergens\":\"NOT_DECLARED\"}");
    }

    @Test
    void searchCatalog_rejectsAHalfFilter_withAMessageForTheModel() {
        CatalogSource source = mock(CatalogSource.class);
        when(source.findCatalog(tenantId)).thenReturn(Optional.of(catalog));

        assertThatThrownBy(() -> new SearchCatalogTool(() -> source)
                .execute(call("search_catalog", Map.of("without_attribute", "allergens")), context))
                .isInstanceOf(InvalidToolArgumentException.class);
    }

    @Test
    void recordUnansweredQuestion_recordsForTheContextNeverTheArguments() {
        KnowledgeGapSink sink = mock(KnowledgeGapSink.class);
        when(sink.recordUnansweredQuestion(eq(tenantId), eq(context.agentId()), eq(context.conversationId()),
                anyString())).thenReturn(KnowledgeGap.record(tenantId, context.agentId(), context.conversationId(), "q", Instant.now()));

        ToolResult result = new RecordUnansweredQuestionTool(() -> sink).execute(call("record_unanswered_question",
                Map.of("question", " ¿Tenéis wifi? ", "tenant_id", UUID.randomUUID().toString())), context);

        assertThat(result.output()).isEqualTo("{\"recorded\":true}");
        verify(sink).recordUnansweredQuestion(tenantId, context.agentId(), context.conversationId(), "¿Tenéis wifi?");
        assertThatThrownBy(() -> new RecordUnansweredQuestionTool(() -> sink)
                .execute(call("record_unanswered_question", Map.of()), context))
                .isInstanceOf(InvalidToolArgumentException.class);
    }

    @Test
    void escalateToOwner_opensTheCaseForTheContext_andTellsTheAgentWhatNotToPromise() {
        HandoffSink sink = mock(HandoffSink.class);
        ArgumentCaptor<Booking.Requester> requester = ArgumentCaptor.forClass(Booking.Requester.class);
        when(sink.open(requester.capture(), any())).thenAnswer(call -> new HandoffSink.HandoffReceipt(
                Handoff.open(call.getArgument(0), call.getArgument(1)), true));

        String output = new EscalateToOwnerTool(() -> sink).execute(call("escalate_to_owner", Map.of(
                "category", "Evento", "summary", "Cumpleaños de 25 personas el 14 de noviembre",
                "contact_name", "Marta", "contact_phone", "+34 600 111 222")), context).output();

        assertThat(requester.getValue().identityId()).isEqualTo(context.identityId());
        assertThat(output).contains("\"owner_notified\":true").contains("\"handoff_code\":")
                .contains("Do not promise prices");
    }

    @Test
    void escalateToOwner_withAnUnknownCategory_isAnArgumentTheModelMustFix() {
        HandoffSink sink = mock(HandoffSink.class);
        when(sink.open(any(), any())).thenThrow(new InvalidHandoffCategoryException("use one of evento, queja"));

        assertThatThrownBy(() -> new EscalateToOwnerTool(() -> sink).execute(call("escalate_to_owner", Map.of(
                "category", "boda", "summary", "s", "contact_name", "Marta")), context))
                .isInstanceOf(InvalidToolArgumentException.class).hasMessageContaining("evento");
    }

    private static ToolCall call(String name, Map<String, Object> input) {
        return new ToolCall("call-1", name, input);
    }
}
