package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.memory.time.DatabaseClock;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The application's clock is the database server's (ADR 0008): its readings fall between two
 * {@code clock_timestamp()} reads of the database, whatever the JVM's clock says, and keep moving
 * inside a transaction instead of freezing at its {@code now()}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DatabaseClockIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private Clock clock;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void theApplicationClock_isTheDatabaseClock() {
        assertThat(clock).isInstanceOf(DatabaseClock.class);
    }

    @Test
    void instant_readsTheDatabaseServersTime_notTheJvms() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Instant before = databaseTime(jdbc);
        Instant reading = clock.instant();
        Instant after = databaseTime(jdbc);

        // Holds with any skew between the JVM and the database (the Docker VM's clock drifts).
        assertThat(reading).isBetween(before, after);
    }

    @Test
    void insideATransaction_readingsKeepMoving_andAreNeverBeforeItsNow() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Instant[] readings = new TransactionTemplate(transactionManager).execute(status -> new Instant[] {
                jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant(),
                clock.instant(),
                clock.instant()});

        assertThat(readings[1]).isAfterOrEqualTo(readings[0]);
        assertThat(readings[2]).isAfter(readings[1]);
    }

    @Test
    void withZone_changesTheZone_notTheSource() {
        Clock madrid = clock.withZone(ZoneId.of("Europe/Madrid"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Instant before = databaseTime(jdbc);
        Instant reading = madrid.instant();
        Instant after = databaseTime(jdbc);

        assertThat(madrid.getZone()).isEqualTo(ZoneId.of("Europe/Madrid"));
        assertThat(reading).isBetween(before, after);
    }

    private static Instant databaseTime(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }
}
