package dev.cauce.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkerIdentityTest {

    @Test
    void getId_returnsHostnamePidUuidSuffix() {
        WorkerIdentity identity = new WorkerIdentity();

        String id = identity.getId();

        assertThat(id).contains(":");
        String[] parts = id.split(":");
        assertThat(parts).as("hostname:pid:uuidShort -> 3 segments").hasSize(3);
        assertThat(parts[0]).as("hostname").isNotBlank();
        assertThat(parts[1]).as("pid").matches("\\d+");
        assertThat(parts[2]).as("uuidShort 8 chars").hasSize(8);
    }

    @Test
    void getId_isStableAcrossInvocations() {
        WorkerIdentity identity = new WorkerIdentity();

        String first = identity.getId();
        String second = identity.getId();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void getId_fitsClaimedByColumn() {
        // claimed_by is VARCHAR(255); the identifier must not exceed it.
        WorkerIdentity identity = new WorkerIdentity();

        assertThat(identity.getId().length()).isLessThanOrEqualTo(255);
    }
}
