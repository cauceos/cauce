package dev.cauce.business.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** JPA mapping of a {@code catalogs} row (V34). {@code items} is the JSON array of items. */
@Entity
@Table(name = "catalogs")
public class CatalogEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "declared_attributes", nullable = false, columnDefinition = "text[]")
    private String[] declaredAttributes = new String[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items", nullable = false)
    private List<Map<String, Object>> items;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CatalogEntity() {
        // for JPA
    }

    public CatalogEntity(UUID id, UUID tenantId, String[] declaredAttributes, List<Map<String, Object>> items,
                         Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.declaredAttributes = declaredAttributes;
        this.items = items;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String[] getDeclaredAttributes() {
        return declaredAttributes;
    }

    public List<Map<String, Object>> getItems() {
        return items;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
