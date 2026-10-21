package dev.cauce.business.profile;

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

/** JPA mapping of a {@code business_profiles} row (V33). {@code facts} is the ordered array of {key, value}. */
@Entity
@Table(name = "business_profiles")
public class BusinessProfileEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "locale", nullable = false, length = 35)
    private String locale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "facts", nullable = false)
    private List<Map<String, String>> facts;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "handoff_categories", nullable = false, columnDefinition = "text[]")
    private String[] handoffCategories = new String[0];

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BusinessProfileEntity() {
        // for JPA
    }

    public BusinessProfileEntity(UUID id, UUID tenantId, String name, String timeZone, String locale,
                                 List<Map<String, String>> facts, String[] handoffCategories, Instant createdAt,
                                 Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.timeZone = timeZone;
        this.locale = locale;
        this.facts = facts;
        this.handoffCategories = handoffCategories;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public String getLocale() {
        return locale;
    }

    public List<Map<String, String>> getFacts() {
        return facts;
    }

    public String[] getHandoffCategories() {
        return handoffCategories;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
