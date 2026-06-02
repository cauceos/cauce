package dev.cauce.tenancy;

import dev.cauce.core.tenant.Tenant;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Inserts the very first operator through a privileged connection that bypasses RLS.
 *
 * <p>Bootstrap runs with no tenant context, so it cannot go through the normal
 * {@code cauce_app} path: Row-Level Security would reject an {@code INSERT} that carries no
 * {@code app.current_tenant_id}. It writes via the owner {@code adminDataSource} — the same
 * connection Flyway uses — which bypasses RLS because the tables are ENABLE (not FORCE) row
 * level security.
 *
 * <p>The {@code adminDataSource} is optional: module integration tests boot a single (owner)
 * datasource with no dedicated admin bean, so we fall back to the primary datasource, which
 * is itself the owner in those tests.
 */
@Component
public class OperatorBootstrap {

    private static final String INSERT_OPERATOR =
            "INSERT INTO tenants (id, parent_tenant_id, tier, name) "
                    + "VALUES (?, NULL, ?, ?) RETURNING created_at, updated_at";

    private final JdbcTemplate jdbc;

    public OperatorBootstrap(@Qualifier("adminDataSource") ObjectProvider<DataSource> adminDataSource,
                             DataSource primaryDataSource) {
        this.jdbc = new JdbcTemplate(adminDataSource.getIfAvailable(() -> primaryDataSource));
    }

    /**
     * Persists {@code operator} (an OPERATOR-tier {@link Tenant}) and returns it rehydrated
     * with the database-assigned timestamps.
     */
    public Tenant insertOperator(Tenant operator) {
        return jdbc.queryForObject(INSERT_OPERATOR, (rs, rowNum) -> Tenant.rehydrate(
                        operator.id(),
                        null,
                        operator.tier(),
                        operator.name(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                operator.id(), operator.tier().name(), operator.name());
    }
}
