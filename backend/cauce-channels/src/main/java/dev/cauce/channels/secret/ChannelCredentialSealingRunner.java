package dev.cauce.channels.secret;

import dev.cauce.core.secret.SecretCipher;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seals, once at startup, the channel credentials stored before encryption at rest existed
 * (ADR 0006, W3). Every new or rewritten credential is sealed by the mapper; this closes the gap
 * for rows that are never rewritten (a config has no update path). Idempotent: a sealed value is
 * left alone, so it does nothing on every start after the first.
 *
 * <p>Runs on the privileged owner connection — the same one Flyway and the operator bootstrap
 * use — because it must see every tenant's rows with no tenant context. It is not an RLS escape
 * hatch on a request path: it reads and rewrites one column, at startup, before any webhook can
 * need it (until then the mapper reads a legacy plaintext value as-is).
 */
@Component
public class ChannelCredentialSealingRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChannelCredentialSealingRunner.class);

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public ChannelCredentialSealingRunner(@Qualifier("adminDataSource") ObjectProvider<DataSource> adminDataSource,
                                          DataSource primaryDataSource, SecretCipher cipher) {
        this.jdbc = new JdbcTemplate(adminDataSource.getIfAvailable(() -> primaryDataSource));
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        sealLegacyCredentials();
    }

    /** Seals every unsealed credential; returns how many rows it rewrote. */
    public int sealLegacyCredentials() {
        List<Map<String, Object>> legacy = jdbc.queryForList(
                "SELECT id, credential FROM channel_configs WHERE credential NOT LIKE 'v1:%'");
        for (Map<String, Object> row : legacy) {
            jdbc.update("UPDATE channel_configs SET credential = ? WHERE id = ?",
                    cipher.seal((String) row.get("credential")), row.get("id"));
        }
        if (!legacy.isEmpty()) {
            log.info("Sealed {} channel credential(s) stored before encryption at rest", legacy.size());
        }
        return legacy.size();
    }
}
