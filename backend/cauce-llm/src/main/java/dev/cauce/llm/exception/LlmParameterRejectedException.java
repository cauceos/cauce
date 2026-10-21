package dev.cauce.llm.exception;

/**
 * The model rejected a sampling parameter the agent configures (e.g. {@code temperature} on a
 * model that no longer accepts it). A configuration error of the agent, not a transient provider
 * failure: the call will never succeed until the parameter is removed from the agent.
 */
public class LlmParameterRejectedException extends LlmInvalidRequestException {

    private final String parameter;

    public LlmParameterRejectedException(String providerId, String modelName, String parameter, String providerDetail) {
        super(providerId, modelName, "The model does not accept the sampling parameter '" + parameter
                + "' the agent configures: remove it from the agent's configuration (" + providerDetail + ")");
        this.parameter = parameter;
    }

    /** The parameter the model rejected, as the API names it ({@code temperature}). */
    public String parameter() {
        return parameter;
    }
}
