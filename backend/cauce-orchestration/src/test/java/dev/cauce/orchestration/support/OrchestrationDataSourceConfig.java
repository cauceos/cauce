package dev.cauce.orchestration.support;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Test-only split datasource so the orchestration integration tests exercise the real
 * runtime path: the app connects as the least-privilege {@code cauce_app} role (subject to
 * RLS), while Flyway and the operator bootstrap use the privileged owner connection.
 *
 * <p>Mirror of {@code dev.cauce.api.DataSourceConfig} (which lives in cauce-api, a module
 * this one does not depend on). Component-scanned by {@code OrchestrationTestApplication};
 * the two prefixes are populated by {@code AbstractOrchestrationIntegrationTest} via
 * {@code @DynamicPropertySource}.
 */
@Configuration(proxyBeanMethods = false)
public class OrchestrationDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties appDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource appDataSource(
            @Qualifier("appDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    @ConfigurationProperties("cauce.admin.datasource")
    public DataSourceProperties adminDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @FlywayDataSource
    public DataSource adminDataSource(
            @Qualifier("adminDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }
}
