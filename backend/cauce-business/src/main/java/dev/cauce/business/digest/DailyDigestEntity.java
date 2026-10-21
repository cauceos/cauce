package dev.cauce.business.digest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** JPA mapping of a {@code daily_digests} row (V38). Written once, never updated. */
@Entity
@Table(name = "daily_digests")
public class DailyDigestEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "local_date", nullable = false, updatable = false)
    private LocalDate localDate;

    @Column(name = "covered_from", nullable = false, updatable = false)
    private Instant coveredFrom;

    @Column(name = "covered_to", nullable = false, updatable = false)
    private Instant coveredTo;

    @Column(name = "booking_count", nullable = false, updatable = false)
    private int bookingCount;

    @Column(name = "handoff_count", nullable = false, updatable = false)
    private int handoffCount;

    @Column(name = "question_count", nullable = false, updatable = false)
    private int questionCount;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Column(name = "notified", nullable = false, updatable = false)
    private boolean notified;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DailyDigestEntity() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public LocalDate getLocalDate() {
        return localDate;
    }

    public Instant getCoveredFrom() {
        return coveredFrom;
    }

    public Instant getCoveredTo() {
        return coveredTo;
    }

    public int getBookingCount() {
        return bookingCount;
    }

    public int getHandoffCount() {
        return handoffCount;
    }

    public int getQuestionCount() {
        return questionCount;
    }

    public String getContent() {
        return content;
    }

    public boolean isNotified() {
        return notified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
