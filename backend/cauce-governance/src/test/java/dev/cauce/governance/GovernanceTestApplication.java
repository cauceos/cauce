package dev.cauce.governance;

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
 * Boot configuration for cauce-governance integration tests, mirroring
 * {@code OrchestrationTestApplication}/{@code ChannelsTestApplication}: scans all of
 * {@code dev.cauce} that is on this module's test classpath (governance, core, memory,
 * tenancy) so the real services, mappers, and the RlsContextAspect participate.
 *
 * <p>The explicit exclude filters keep nested {@code @TestConfiguration} classes out of the
 * component scan so they do not collide across the cached test context.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "dev.cauce", excludeFilters = {
        @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
@EntityScan("dev.cauce")
@EnableJpaRepositories("dev.cauce")
public class GovernanceTestApplication {
}
