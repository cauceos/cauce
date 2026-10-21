package dev.cauce.core.business.booking;

/** No agenda is loaded for the tenant, or the tenant is not visible. */
public class AgendaNotFoundException extends RuntimeException {

    public AgendaNotFoundException(String message) {
        super(message);
    }
}
