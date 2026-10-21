package dev.cauce.business.digest;

import dev.cauce.core.tenant.TenantContext;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends each business its nightly summary once its local time has come (ADR 0007, B10). Every tick
 * reads, across tenants, who wants a summary and when; for each business whose local time is past
 * its summary time, it runs today's summary under that tenant's context — a no-op once today's
 * exists. A business whose summary time passes while the instance is down still gets it that same
 * local day; a day that ended with the instance down is not sent late. See
 * {@link DailyDigestProperties}.
 */
@Component
public class DailyDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyDigestScheduler.class);

    private final DailyDigestService digests;
    private final Clock clock;
    private final DailyDigestProperties properties;

    public DailyDigestScheduler(DailyDigestService digests, @Qualifier("businessClock") Clock clock,
                                DailyDigestProperties properties) {
        this.digests = digests;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${cauce.business.digest.interval-ms:60000}")
    public void scheduledRun() {
        if (properties.isEnabled()) {
            runDue();
        }
    }

    /** Runs the summaries that are due now; returns how many businesses were due. */
    public int runDue() {
        int due = 0;
        for (DailyDigestRepository.Candidate candidate : digests.candidates()) {
            LocalTime localNow;
            try {
                localNow = LocalTime.ofInstant(clock.instant(), ZoneId.of(candidate.getTimeZone()));
            } catch (DateTimeException e) {
                log.warn("Business {} has an unknown time zone '{}'; no summary", candidate.getTenantId(),
                        candidate.getTimeZone());
                continue;
            }
            if (localNow.isBefore(candidate.getDailyDigestTime())) {
                continue;
            }
            due++;
            TenantContext.setCurrentTenantId(candidate.getTenantId());
            try {
                digests.runToday(candidate.getTenantId());
            } catch (RuntimeException e) {
                log.warn("The summary of business {} failed; the next tick retries it: {}",
                        candidate.getTenantId(), e.toString(), e);
            } finally {
                TenantContext.clear();
            }
        }
        return due;
    }
}
