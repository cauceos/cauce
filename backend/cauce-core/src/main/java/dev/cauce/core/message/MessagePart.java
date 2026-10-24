package dev.cauce.core.message;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.Objects;

/**
 * One part of a {@link Message}, which is an ordered list of them (ADR 0004, decision 2): a text
 * message is a list of one text part, a tool message a list of one tool part. Closed: a new kind
 * of part (audio, ADR 0012) is a new case here, and every switch over the parts has to decide it.
 */
public sealed interface MessagePart permits MessagePart.Text, MessagePart.ToolCallPart, MessagePart.ToolResultPart {

    /** The part's kind as the stored form and the content hash v2 name it ({@link MessageParts}). */
    String type();

    /** Plain UTF-8 text, preserved verbatim (never trimmed). */
    record Text(String text) implements MessagePart {

        public static final String TYPE = "text";

        public Text {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** The agent's request to run a tool: what {@code tool_content} carried for a TOOL_CALL. */
    record ToolCallPart(ToolCall call) implements MessagePart {

        public static final String TYPE = "tool_call";

        public ToolCallPart {
            Objects.requireNonNull(call, "call");
        }

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** The output of a tool call: what {@code tool_content} carried for a TOOL_RESULT. */
    record ToolResultPart(ToolResult result) implements MessagePart {

        public static final String TYPE = "tool_result";

        public ToolResultPart {
            Objects.requireNonNull(result, "result");
        }

        @Override
        public String type() {
            return TYPE;
        }
    }
}
