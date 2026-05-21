package dev.cauce.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only service used to observe the database session state established by
 * RlsContextAspect inside a transaction. Being a {@code @Transactional} method of a
 * {@code @Service}, the aspect applies and sets the GUC before this reads it back.
 */
@Service
public class RlsProbeService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public UUID currentContext() {
        Object value = entityManager
                .createNativeQuery("SELECT current_setting('app.current_tenant_id', true)")
                .getSingleResult();
        if (value == null || value.toString().isEmpty()) {
            return null;
        }
        return UUID.fromString(value.toString());
    }
}
