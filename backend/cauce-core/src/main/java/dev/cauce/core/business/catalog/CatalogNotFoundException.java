package dev.cauce.core.business.catalog;

/** No catalog is loaded for the tenant, or the tenant is not visible. */
public class CatalogNotFoundException extends RuntimeException {

    public CatalogNotFoundException(String message) {
        super(message);
    }
}
