package dev.cauce.api;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA wiring for the aggregating application. {@code @EntityScan} and
 * {@code @EnableJpaRepositories} are widened to {@code dev.cauce} so entities and Spring Data
 * repositories defined in the sibling modules are discovered (they default to this class's
 * package otherwise).
 *
 * <p>These deliberately live here rather than on {@link CauceApplication}: keeping them off the
 * main {@code @SpringBootApplication} class lets sliced web tests ({@code @WebMvcTest}) load the
 * controller layer without dragging in JPA infrastructure, which would otherwise fail for lack
 * of a datasource. At runtime this {@code @Configuration} is component-scanned exactly as before,
 * so behaviour is unchanged.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan("dev.cauce")
@EnableJpaRepositories("dev.cauce")
public class PersistenceConfig {
}
