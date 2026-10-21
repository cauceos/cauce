package dev.cauce.business;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Module beans of cauce-business. */
@Configuration
public class BusinessConfig {

    /** The time the business capabilities measure holds, shifts and digests against. */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
