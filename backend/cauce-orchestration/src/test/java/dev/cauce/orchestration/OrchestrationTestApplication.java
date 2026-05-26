package dev.cauce.orchestration;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Spring Boot configuration for cauce-orchestration integration tests. Mirrors the
 * application's scanning so beans across the modules are wired: components/services/the
 * aspect ({@code dev.cauce}), JPA entities, and Spring Data repositories.
 *
 * <p>The {@link TypeExcludeFilter} and {@link AutoConfigurationExcludeFilter} are the
 * filters that {@code @SpringBootApplication} installs by default; we apply them
 * explicitly here because {@code @SpringBootConfiguration} alone does not. Without them,
 * every {@code @TestConfiguration} nested inside an IT class is picked up by this scan
 * and a second IT that defines a {@code @TestConfiguration} bean of the same name
 * collides on the cached context with a {@code BeanDefinitionOverrideException}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "dev.cauce", excludeFilters = {
        @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
@EntityScan("dev.cauce")
@EnableJpaRepositories("dev.cauce")
public class OrchestrationTestApplication {
}
