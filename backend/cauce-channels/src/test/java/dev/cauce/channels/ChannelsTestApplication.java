package dev.cauce.channels;

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
 * Spring Boot configuration for cauce-channels integration tests. Mirrors the
 * application's scanning so beans across the modules are wired: components/services/the
 * aspect ({@code dev.cauce}), JPA entities, and Spring Data repositories. Exact mirror of
 * {@code OrchestrationTestApplication} — see its javadoc for why the default
 * {@code @SpringBootApplication} exclude filters are applied explicitly.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "dev.cauce", excludeFilters = {
        @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
@EntityScan("dev.cauce")
@EnableJpaRepositories("dev.cauce")
public class ChannelsTestApplication {
}
