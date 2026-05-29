package dev.cauce.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Cauce backend application.
 *
 * <p>This is the aggregating application module. Component scanning is widened to
 * {@code dev.cauce} so beans defined in the sibling modules are discovered. JPA entity and
 * repository scanning live in {@link PersistenceConfig} (kept off this class so sliced web
 * tests need not bootstrap JPA).
 */
@SpringBootApplication(scanBasePackages = "dev.cauce")
public class CauceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CauceApplication.class, args);
    }
}
