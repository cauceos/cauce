package dev.cauce.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidGeneratorTest {

    @Test
    void newV7_generatesVersion7() {
        assertThat(UuidGenerator.newV7().version()).isEqualTo(7);
    }

    @Test
    void newV7_successiveCalls_produceDistinctValues() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            ids.add(UuidGenerator.newV7());
        }
        assertThat(ids).hasSize(1_000);
    }

    @Test
    void newV7_idsAreTimeOrdered_lexicographically() throws InterruptedException {
        List<UUID> creationOrder = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            creationOrder.add(UuidGenerator.newV7());
            Thread.sleep(2); // distinct millisecond => deterministic v7 ordering
        }

        // Canonical string order matches the unsigned byte order PostgreSQL sorts on.
        List<UUID> byStringOrder = creationOrder.stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

        assertThat(byStringOrder).containsExactlyElementsOf(creationOrder);
    }
}
