package dev.cauce.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entry point for the Cauce backend application.
 *
 * <p>This is the aggregating application module. Component, entity, and repository
 * scanning are widened to {@code dev.cauce} so beans, JPA entities, and Spring Data
 * repositories defined in the sibling modules are discovered. {@code @EntityScan} and
 * {@code @EnableJpaRepositories} are declared explicitly because they default to the
 * package of this class ({@code dev.cauce.api}), not to {@code scanBasePackages}.
 */
@SpringBootApplication(scanBasePackages = "dev.cauce")
@EntityScan("dev.cauce")
@EnableJpaRepositories("dev.cauce")
public class CauceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CauceApplication.class, args);
    }
}
