package dev.cauce.tools.business;

import dev.cauce.core.audit.CanonicalJson;
import dev.cauce.core.business.gap.KnowledgeGap;
import dev.cauce.core.business.gap.KnowledgeGapSink;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.tools.spi.Tool;
import dev.cauce.tools.spi.ToolArguments;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Records a question the business information does not answer (ADR 0007, B4), for the owner to
 * read in the daily digest. The question is recorded for the agent's tenant, agent and
 * conversation — taken from the context, never from the model.
 */
public final class RecordUnansweredQuestionTool implements Tool {

    static final String NAME = "record_unanswered_question";

    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Records a question that the business information and the catalog do not answer, so the owner "
                    + "can add the answer. Call it whenever you have to tell someone that something is not known.",
            Map.of("type", "object",
                    "properties", Map.of("question", Map.of("type", "string",
                            "description", "The question as the person asked it.")),
                    "required", List.of("question"),
                    "additionalProperties", false));

    private final Supplier<KnowledgeGapSink> sinks;

    public RecordUnansweredQuestionTool(Supplier<KnowledgeGapSink> sinks) {
        this.sinks = sinks;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        String question = ToolArguments.of(call).requiredText("question");
        if (question.length() > KnowledgeGap.MAX_QUESTION_LENGTH) {
            question = question.substring(0, KnowledgeGap.MAX_QUESTION_LENGTH);
        }
        KnowledgeGapSink sink = sinks.get();
        if (sink == null) {
            return ToolResult.error(call.toolCallId(), NAME, "Unanswered questions cannot be recorded here.");
        }
        sink.recordUnansweredQuestion(context.tenantId(), context.agentId(), context.conversationId(), question);
        return ToolResult.success(call.toolCallId(), NAME, CanonicalJson.serialize(Map.of("recorded", true)));
    }
}
