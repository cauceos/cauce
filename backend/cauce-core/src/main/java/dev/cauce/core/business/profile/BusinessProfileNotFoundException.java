package dev.cauce.core.business.profile;

/** No business profile is loaded for the tenant, or the tenant is not visible. */
public class BusinessProfileNotFoundException extends RuntimeException {

    public BusinessProfileNotFoundException(String message) {
        super(message);
    }
}
