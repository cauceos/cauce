package dev.cauce.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Cauce backend application.
 *
 * <p>This is the aggregating application module. As the platform grows, component scanning
 * across the {@code dev.cauce.*} modules will be configured here explicitly; for now the
 * skeleton boots an empty context.
 */
@SpringBootApplication
public class CauceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CauceApplication.class, args);
    }
}
