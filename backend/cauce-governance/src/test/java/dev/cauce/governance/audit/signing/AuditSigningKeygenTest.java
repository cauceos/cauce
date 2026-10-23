package dev.cauce.governance.audit.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code --into} mode: the key a local instance signs with from its first start. */
class AuditSigningKeygenTest {

    @TempDir
    private Path directory;

    private String read(String name) throws IOException {
        return Files.readString(directory.resolve(name));
    }

    @Test
    void writeInto_emptyDirectory_writesAPairTheInstanceAcceptsAndTheRegistryNames() throws IOException {
        assertThat(AuditSigningKeygen.writeInto(directory)).isTrue();

        AuditSigningProperties properties = new AuditSigningProperties();
        properties.setPrivateKey(read(AuditSigningKeygen.PRIVATE_KEY_FILE));
        properties.setPublicKey(read(AuditSigningKeygen.PUBLIC_KEY_FILE));
        properties.setRegistryPath(directory.resolve(AuditSigningKeygen.REGISTRY_FILE).toString());
        AuditSigningConfiguration configuration = new AuditSigningConfiguration();
        // The startup self-test signs a probe with the private key and checks it with the public one.
        AuditEntrySigner signer = configuration.auditEntrySigner(properties);
        AuditKeyRegistry registry = configuration.auditKeyRegistry(properties);

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.find(signer.keyId())).isPresent();
    }

    @Test
    void writeInto_keyAlreadyPresent_leavesItUnchanged() throws IOException {
        AuditSigningKeygen.writeInto(directory);
        String privateKey = read(AuditSigningKeygen.PRIVATE_KEY_FILE);
        String registry = read(AuditSigningKeygen.REGISTRY_FILE);

        assertThat(AuditSigningKeygen.writeInto(directory)).isFalse();

        assertThat(read(AuditSigningKeygen.PRIVATE_KEY_FILE)).isEqualTo(privateKey);
        assertThat(read(AuditSigningKeygen.REGISTRY_FILE)).isEqualTo(registry);
    }

    @Test
    void writeInto_partialKey_refusesToTouchIt() throws IOException {
        Files.writeString(directory.resolve(AuditSigningKeygen.PUBLIC_KEY_FILE), "left over");

        assertThatThrownBy(() -> AuditSigningKeygen.writeInto(directory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing");
        assertThat(directory.resolve(AuditSigningKeygen.PRIVATE_KEY_FILE)).doesNotExist();
        assertThat(read(AuditSigningKeygen.PUBLIC_KEY_FILE)).isEqualTo("left over");
    }

    @Test
    void writeInto_missingDirectory_createsIt() {
        Path nested = directory.resolve("keys");

        assertThat(AuditSigningKeygen.writeInto(nested)).isTrue();

        assertThat(nested.resolve(AuditSigningKeygen.REGISTRY_FILE)).exists();
    }
}
