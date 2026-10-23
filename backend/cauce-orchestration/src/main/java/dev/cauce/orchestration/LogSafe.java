package dev.cauce.orchestration;

import dev.cauce.llm.exception.LlmProviderException;

/**
 * How a failure on the message path goes into a log line without what it might carry (ADR 0011,
 * L2): a provider's error can echo the prompt, a database error the values of a row, a tool's the
 * arguments it was given. The full detail stays where it already is, the invocation's
 * {@code last_error} and its SYSTEM message, and in DEBUG logs, off in production.
 */
public final class LogSafe {

    private LogSafe() {
    }

    /**
     * The failure's type; for a provider failure, the provider and model too; for one of Cauce's
     * own exceptions, its message as well, which by convention names ids, never content.
     */
    public static String describe(Throwable e) {
        if (e instanceof LlmProviderException provider) {
            return e.getClass().getSimpleName() + "[provider=" + provider.providerId() + ", model="
                    + provider.modelName() + "]";
        }
        if (e.getClass().getName().startsWith("dev.cauce.") && e.getMessage() != null) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return type(e);
    }

    /** The failure's type only: for what may echo its input, like a tool's exception. */
    public static String type(Throwable e) {
        return e.getClass().getSimpleName();
    }
}
