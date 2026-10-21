package dev.cauce.business;

import dev.cauce.business.digest.DailyDigestProperties;
import dev.cauce.business.owner.OwnerNotificationProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Module beans of cauce-business. {@link EnableScheduling} powers the owner-notification dispatcher
 * and the daily digest (self-contained, like the other modules' configurations).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({OwnerNotificationProperties.class, DailyDigestProperties.class})
public class BusinessConfig {

    /** The time the business capabilities measure holds, shifts and digests against. */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
