package dev.cauce.api;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Splits the database into two connections so Row-Level Security is enforced at runtime.
 *
 * <ul>
 *   <li>{@code appDataSource} ({@code @Primary}) connects as the least-privilege
 *       {@code cauce_app} role and backs JPA, the repositories, and {@code RlsContextAspect}.
 *       It is NOSUPERUSER/NOBYPASSRLS and not a table owner, so every query is filtered by
 *       the {@code hierarchical_visibility} policies. Bound from {@code spring.datasource.*}.</li>
 *   <li>{@code adminDataSource} connects as the privileged owner role. It is used only by
 *       Flyway ({@link FlywayDataSource}) and the operator bootstrap, both of which must
 *       bypass RLS — which they do because every table is ENABLE (not FORCE) row level
 *       security, so the owner is exempt. Bound from {@code cauce.admin.datasource.*}.</li>
 * </ul>
 *
 * <p>Declaring explicit {@link DataSource} beans switches off Spring Boot's single-datasource
 * auto-configuration; the {@code JpaTransactionManager} and Hibernate use the {@code @Primary}
 * one, so all tenant-scoped work runs as {@code cauce_app}.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

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
