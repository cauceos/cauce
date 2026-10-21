package dev.cauce.business.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** JPA mapping of an {@code agendas} row (V36). {@code shifts} is the JSON array of shifts. */
@Entity
@Table(name = "agendas")
public class AgendaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "unit", nullable = false, length = 40)
    private String unit;

    @Column(name = "max_party_size", nullable = false)
    private int maxPartySize;

    @Column(name = "hold_minutes", nullable = false)
    private int holdMinutes;

    @Column(name = "horizon_days", nullable = false)
    private int horizonDays;

    @Column(name = "require_phone", nullable = false)
    private boolean requirePhone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shifts", nullable = false)
    private List<Map<String, Object>> shifts;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "closed_dates", nullable = false, columnDefinition = "date[]")
    private LocalDate[] closedDates = new LocalDate[0];

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgendaEntity() {
        // for JPA
    }

    public AgendaEntity(UUID id, UUID tenantId, String unit, int maxPartySize, int holdMinutes, int horizonDays,
                        boolean requirePhone, List<Map<String, Object>> shifts, LocalDate[] closedDates,
                        Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.unit = unit;
        this.maxPartySize = maxPartySize;
        this.holdMinutes = holdMinutes;
        this.horizonDays = horizonDays;
        this.requirePhone = requirePhone;
        this.shifts = shifts;
        this.closedDates = closedDates;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getUnit() {
        return unit;
    }

    public int getMaxPartySize() {
        return maxPartySize;
    }

    public int getHoldMinutes() {
        return holdMinutes;
    }

    public int getHorizonDays() {
        return horizonDays;
    }

    public boolean isRequirePhone() {
        return requirePhone;
    }

    public List<Map<String, Object>> getShifts() {
        return shifts;
    }

    public LocalDate[] getClosedDates() {
        return closedDates;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
