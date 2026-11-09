package dev.cauce.channels.webchat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes expired web-chat sessions once a day (ADR 0014, WC2): their token hashes stop existing
 * once they no longer authenticate. The cutoff is the database's now (ADR 0008). Disabled with
 * {@code cauce.web.sessions.purge.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "cauce.web.sessions.purge", name = "enabled", matchIfMissing = true)
public class WebSessionPurger {

    private static final Logger log = LoggerFactory.getLogger(WebSessionPurger.class);

    private final WebSessionService sessions;

    public WebSessionPurger(WebSessionService sessions) {
        this.sessions = sessions;
    }

    @Scheduled(cron = "${cauce.web.sessions.purge.cron:0 40 3 * * *}")
    public void purge() {
        int purged = sessions.purgeExpired();
        if (purged > 0) {
            log.info("Purged {} expired web-chat sessions", purged);
        }
    }
}
