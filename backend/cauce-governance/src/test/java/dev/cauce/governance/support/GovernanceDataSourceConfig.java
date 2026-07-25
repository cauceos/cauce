package dev.cauce.governance.support;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Mirror of {@code dev.cauce.api.DataSourceConfig} (and the orchestration/channels test
 * copies) for cauce-governance integration tests: the {@code @Primary} application
 * datasource connects as the least-privilege {@code cauce_app} role (subject to RLS and to
 * the V21 append-only revoke), while the admin datasource (the container owner) runs Flyway
 * and privileged test setup.
 */
@Configuration(proxyBeanMethods = false)
public class GovernanceDataSourceConfig {

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
