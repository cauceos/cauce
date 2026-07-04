package dev.cauce.channels;

/**
 * Thrown when a provider webhook request fails the adapter's authenticity check (missing
 * or wrong channel secret / signature). The request is never processed. The message must
 * never include the presented secret.
 */
public class WebhookAuthenticationException extends RuntimeException {

    public WebhookAuthenticationException(String message) {
        super(message);
    }
}
