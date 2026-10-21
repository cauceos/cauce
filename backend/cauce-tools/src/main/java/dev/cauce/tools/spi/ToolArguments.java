package dev.cauce.tools.spi;

import dev.cauce.core.tool.ToolCall;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the arguments of a {@link ToolCall} the model produced. Models send numbers as numbers or
 * as text, and leave optional arguments out or empty; this normalizes both. A missing or
 * malformed required argument is a {@link InvalidToolArgumentException}, which a tool turns into
 * an error result the model can correct.
 */
public final class ToolArguments {

    private final Map<String, Object> input;

    private ToolArguments(Map<String, Object> input) {
        this.input = input;
    }

    public static ToolArguments of(ToolCall call) {
        return new ToolArguments(call.input());
    }

    /** The text argument {@code name}, stripped; empty when absent or blank. */
    public Optional<String> text(String name) {
        Object value = input.get(name);
        if (value == null) {
            return Optional.empty();
        }
        String text = value.toString().strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /** The required text argument {@code name}. */
    public String requiredText(String name) {
        return text(name).orElseThrow(() -> new InvalidToolArgumentException("'" + name + "' is required"));
    }

    /** The whole-number argument {@code name}; empty when absent. */
    public Optional<Integer> integer(String name) {
        Object value = input.get(name);
        if (value == null || value.toString().isBlank()) {
            return Optional.empty();
        }
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return Optional.of(number.intValue());
        }
        try {
            return Optional.of(Integer.parseInt(value.toString().strip()));
        } catch (NumberFormatException e) {
            throw new InvalidToolArgumentException("'" + name + "' must be a whole number");
        }
    }

    /** The required whole-number argument {@code name}. */
    public int requiredInteger(String name) {
        return integer(name).orElseThrow(() -> new InvalidToolArgumentException("'" + name + "' is required"));
    }

    /** A required or malformed argument the model must correct; its message is safe to return. */
    public static class InvalidToolArgumentException extends RuntimeException {

        public InvalidToolArgumentException(String message) {
            super(message);
        }
    }
}
