package dev.cauce.tools.business;

import dev.cauce.core.audit.CanonicalJson;
import dev.cauce.core.business.booking.Booking;
import dev.cauce.core.business.handoff.Handoff;
import dev.cauce.core.business.handoff.HandoffSink;
import dev.cauce.core.business.handoff.HandoffSink.HandoffReceipt;
import dev.cauce.core.business.handoff.InvalidHandoffCategoryException;
import dev.cauce.core.business.handoff.UnquotedCustomerMessageException;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.tools.spi.Tool;
import dev.cauce.tools.spi.ToolArguments;
import dev.cauce.tools.spi.ToolArguments.InvalidToolArgumentException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Hands a case to the owner (ADR 0007, B7): records it with what the agent collected, queues the
 * owner's notice and tells the agent what to say. The case belongs to the conversation of the
 * execution context; the conversation stays open and the agent keeps talking.
 */
public final class EscalateToOwnerTool implements Tool {

    static final String NAME = "escalate_to_owner";
    static final String NEXT_STEP = "Tell the person that the owner has the request and will contact them. Do not "
            + "promise prices, availability, discounts or exceptions, and do not say when they will be contacted.";

    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Hands a case to the owner of the business: large groups, events, complaints, special requests, or "
                    + "anything you cannot answer or decide. Collect first the person's name, a way to reach them "
                    + "and the details, then call it once per case.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "category", Map.of("type", "string",
                                    "description", "The kind of case, one of the business's categories."),
                            "summary", Map.of("type", "string",
                                    "description", "Your account of what the person wants, with every detail they "
                                            + "gave: dates, number of people, what happened. Do not paste their "
                                            + "messages into it: quote them in customer_message."),
                            "customer_message", Map.of("type", "string",
                                    "description", "Optional: the person's request in their own words, copied "
                                            + "word for word from one of their messages (the one that best states "
                                            + "it). Never paraphrase: a quote that is not theirs is refused."),
                            "contact_name", Map.of("type", "string", "description", "The person's name."),
                            "contact_phone", Map.of("type", "string",
                                    "description", "A phone number to reach them, if they gave one.")),
                    "required", List.of("category", "summary", "contact_name"),
                    "additionalProperties", false));

    private final Supplier<HandoffSink> sinks;

    public EscalateToOwnerTool(Supplier<HandoffSink> sinks) {
        this.sinks = sinks;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        ToolArguments args = ToolArguments.of(call);
        HandoffSink sink = sinks.get();
        if (sink == null) {
            return ToolResult.error(call.toolCallId(), NAME, "Cases cannot be handed to the owner here.");
        }
        Handoff.Request request;
        try {
            request = new Handoff.Request(args.requiredText("category"), args.requiredText("summary"),
                    args.requiredText("contact_name"), args.text("contact_phone").orElse(null),
                    args.text("customer_message").orElse(null));
        } catch (IllegalArgumentException e) {
            throw new InvalidToolArgumentException(e.getMessage());
        }
        HandoffReceipt receipt;
        try {
            receipt = sink.open(new Booking.Requester(context.tenantId(), context.agentId(), context.conversationId(),
                    context.identityId(), context.triggerMessageId()), request);
        } catch (InvalidHandoffCategoryException | UnquotedCustomerMessageException e) {
            throw new InvalidToolArgumentException(e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("handoff_code", receipt.handoff().code());
        out.put("owner_notified", receipt.ownerNotified());
        out.put("next_step", NEXT_STEP);
        return ToolResult.success(call.toolCallId(), NAME, CanonicalJson.serialize(out));
    }
}
