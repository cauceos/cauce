package dev.cauce.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    void setThenGet_returnsSameId() {
        UUID id = UUID.randomUUID();
        TenantContext.setCurrentTenantId(id);
        assertThat(TenantContext.getCurrentTenantId()).contains(id);
    }

    @Test
    void clear_leavesContextEmpty() {
        TenantContext.setCurrentTenantId(UUID.randomUUID());
        TenantContext.clear();
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    @Test
    void value_isNotVisibleFromAnotherThread() throws InterruptedException {
        TenantContext.setCurrentTenantId(UUID.randomUUID());

        AtomicReference<Optional<UUID>> seenInOtherThread = new AtomicReference<>();
        Thread other = new Thread(() -> seenInOtherThread.set(TenantContext.getCurrentTenantId()));
        other.start();
        other.join();

        assertThat(seenInOtherThread.get()).isEmpty();
    }
}
