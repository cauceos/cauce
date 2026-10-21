package dev.cauce.memory.time;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The application's only {@link Clock}: the database server's (ADR 0008). Every date that is
 * written to be compared later — a hold's expiry, a retry's next attempt, a notice's due time, a
 * link code's validity — and every comparison against "now" in Java reads this clock, so they
 * agree with the {@code now()} the queries compare against whatever the application host's clock
 * says.
 *
 * <p>Each reading is {@code clock_timestamp()} on the current transaction's connection (a
 * short-lived one outside a transaction): the server's wall time at that statement, which keeps
 * moving inside a transaction, unlike {@code now()}, so two stamps in one transaction keep their
 * order. A later transaction's {@code now()} is never earlier than it.
 */
@Component
public final class DatabaseClock extends Clock {

    private final EntityManager entityManager;
    private final ZoneId zone;

    @Autowired
    public DatabaseClock(EntityManager entityManager) {
        this(entityManager, ZoneOffset.UTC);
    }

    private DatabaseClock(EntityManager entityManager, ZoneId zone) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public Instant instant() {
        Object now = entityManager.createNativeQuery("SELECT clock_timestamp()").getSingleResult();
        return switch (now) {
            case OffsetDateTime offset -> offset.toInstant();
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            default -> throw new IllegalStateException("clock_timestamp() returned " + now.getClass().getName());
        };
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return zone.equals(this.zone) ? this : new DatabaseClock(entityManager, zone);
    }
}
