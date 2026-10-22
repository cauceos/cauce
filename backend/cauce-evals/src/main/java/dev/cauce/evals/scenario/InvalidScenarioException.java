package dev.cauce.evals.scenario;

/** A scenario file the closed format rejects: it is fixed in the file, never skipped. */
public class InvalidScenarioException extends RuntimeException {

    public InvalidScenarioException(String message) {
        super(message);
    }

    public InvalidScenarioException(String message, Throwable cause) {
        super(message, cause);
    }
}
