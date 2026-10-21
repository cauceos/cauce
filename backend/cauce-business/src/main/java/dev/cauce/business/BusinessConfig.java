package dev.cauce.business;

import dev.cauce.business.digest.DailyDigestProperties;
import dev.cauce.business.owner.OwnerNotificationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Module beans of cauce-business. {@link EnableScheduling} powers the owner-notification dispatcher
 * and the daily digest (self-contained, like the other modules' configurations). Holds, shifts,
 * link codes and digests are measured against the application's {@link java.time.Clock}, the
 * database's (ADR 0008); this module defines none of its own.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({OwnerNotificationProperties.class, DailyDigestProperties.class})
public class BusinessConfig {
}
