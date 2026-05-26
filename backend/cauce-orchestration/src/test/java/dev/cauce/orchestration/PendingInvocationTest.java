package dev.cauce.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.orchestration.exception.InvalidPendingInvocationTransitionException;
import dev.cauce.orchestration.exception.MaxRetriesExceededException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingInvocationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID TRIGGER = UUID.randomUUID();
    private static final long BASE_INTERVAL = 30L;

    private static PendingInvocation pending() {
        return PendingInvocation.create(TENANT, CONVERSATION, TRIGGER);
    }

    @Test
    void create_whenValid_startsPendingWithDefaults() {
        PendingInvocation invocation = pending();

        assertThat(invocation.id()).isNotNull();
        assertThat(invocation.id().version()).isEqualTo(7);
        assertThat(invocation.tenantId()).isEqualTo(TENANT);
        assertThat(invocation.conversationId()).isEqualTo(CONVERSATION);
        assertThat(invocation.triggerMessageId()).isEqualTo(TRIGGER);
        assertThat(invocation.status()).isEqualTo(PendingInvocationStatus.PENDING);
        assertThat(invocation.attemptCount()).isZero();
        assertThat(invocation.maxAttempts()).isEqualTo(3);
        assertThat(invocation.lastAttemptAt()).isNull();
        assertThat(invocation.lastError()).isNull();
        assertThat(invocation.createdAt()).isNotNull();
        assertThat(invocation.claimedAt()).isNull();
        assertThat(invocation.claimedBy()).isNull();
        assertThat(invocation.completedAt()).isNull();
        assertThat(invocation.nextAttemptAt()).isNull();
    }

    @Test
    void create_whenTenantIdNull_throwsNpe() {
        assertThatThrownBy(() -> PendingInvocation.create(null, CONVERSATION, TRIGGER))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("tenantId");
    }

    @Test
    void create_whenConversationIdNull_throwsNpe() {
        assertThatThrownBy(() -> PendingInvocation.create(TENANT, null, TRIGGER))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("conversationId");
    }

    @Test
    void create_whenTriggerMessageIdNull_throwsNpe() {
        assertThatThrownBy(() -> PendingInvocation.create(TENANT, CONVERSATION, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("triggerMessageId");
    }

    @Test
    void claim_whenPending_transitionsToProcessing() {
        PendingInvocation claimed = pending().claim("worker-1");

        assertThat(claimed.status()).isEqualTo(PendingInvocationStatus.PROCESSING);
        assertThat(claimed.attemptCount()).isEqualTo(1);
        assertThat(claimed.claimedBy()).isEqualTo("worker-1");
        assertThat(claimed.claimedAt()).isNotNull();
        assertThat(claimed.lastAttemptAt()).isNotNull();
        assertThat(claimed.nextAttemptAt()).isNull();
    }

    @Test
    void claim_whenBlankWorker_throwsIllegalArgument() {
        assertThatThrownBy(() -> pending().claim("  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workerId");
    }

    @Test
    void claim_whenNotPending_throwsInvalidTransition() {
        PendingInvocation processing = pending().claim("worker-1");

        assertThatThrownBy(() -> processing.claim("worker-2"))
                .isInstanceOf(InvalidPendingInvocationTransitionException.class);
    }

    @Test
    void complete_whenProcessing_transitionsToCompleted() {
        PendingInvocation completed = pending().claim("worker-1").complete();

        assertThat(completed.status()).isEqualTo(PendingInvocationStatus.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.nextAttemptAt()).isNull();
    }

    @Test
    void complete_whenPending_throwsInvalidTransition() {
        assertThatThrownBy(() -> pending().complete())
                .isInstanceOf(InvalidPendingInvocationTransitionException.class);
    }

    @Test
    void releaseForRetry_whenProcessing_returnsPendingClearsClaimAndSchedulesBackoff() {
        Instant before = Instant.now();
        PendingInvocation released =
                pending().claim("worker-1").releaseForRetry("429 rate limited", BASE_INTERVAL);

        assertThat(released.status()).isEqualTo(PendingInvocationStatus.PENDING);
        assertThat(released.claimedAt()).isNull();
        assertThat(released.claimedBy()).isNull();
        assertThat(released.lastError()).isEqualTo("429 rate limited");
        assertThat(released.attemptCount()).isEqualTo(1);
        assertThat(released.nextAttemptAt())
                .as("attempt 1 -> base * 2^0 = base seconds in the future")
                .isAfterOrEqualTo(before.plusSeconds(BASE_INTERVAL))
                .isBeforeOrEqualTo(Instant.now().plusSeconds(BASE_INTERVAL + 5));
    }

    @Test
    void releaseForRetry_secondAttempt_schedulesDoubleBackoff() {
        Instant before = Instant.now();
        PendingInvocation released = pending()
                .claim("w1").releaseForRetry("e1", BASE_INTERVAL)
                .claim("w2").releaseForRetry("e2", BASE_INTERVAL);

        assertThat(released.attemptCount()).isEqualTo(2);
        assertThat(released.nextAttemptAt())
                .as("attempt 2 -> base * 2^1 = 2x base seconds")
                .isAfterOrEqualTo(before.plus(Duration.ofSeconds(BASE_INTERVAL * 2L)))
                .isBeforeOrEqualTo(Instant.now().plus(Duration.ofSeconds(BASE_INTERVAL * 2L + 5)));
    }

    @Test
    void releaseForRetry_thirdAttempt_schedulesQuadrupleBackoff() {
        // Bump the budget so we can release after attempt 3 instead of hitting the max.
        PendingInvocation third = PendingInvocation.rehydrate(UUID.randomUUID(), TENANT, CONVERSATION,
                TRIGGER, PendingInvocationStatus.PROCESSING, 3, 5, Instant.now(), "prev",
                Instant.now(), Instant.now(), "w3", null, null);

        Instant before = Instant.now();
        PendingInvocation released = third.releaseForRetry("transient", BASE_INTERVAL);

        assertThat(released.attemptCount()).isEqualTo(3);
        assertThat(released.nextAttemptAt())
                .as("attempt 3 -> base * 2^2 = 4x base seconds")
                .isAfterOrEqualTo(before.plus(Duration.ofSeconds(BASE_INTERVAL * 4L)))
                .isBeforeOrEqualTo(Instant.now().plus(Duration.ofSeconds(BASE_INTERVAL * 4L + 5)));
    }

    @Test
    void releaseForRetry_canBeReclaimedAndCompleted() {
        PendingInvocation completed = pending()
                .claim("worker-1")
                .releaseForRetry("transient", BASE_INTERVAL)
                .claim("worker-2")
                .complete();

        assertThat(completed.status()).isEqualTo(PendingInvocationStatus.COMPLETED);
        assertThat(completed.attemptCount()).isEqualTo(2);
    }

    @Test
    void releaseForRetry_whenNotProcessing_throwsInvalidTransition() {
        assertThatThrownBy(() -> pending().releaseForRetry("x", BASE_INTERVAL))
                .isInstanceOf(InvalidPendingInvocationTransitionException.class);
    }

    @Test
    void releaseForRetry_whenAttemptsExhausted_throwsMaxRetriesExceeded() {
        // Three claims consume the default budget of 3; the third release must be rejected.
        PendingInvocation afterThirdClaim = pending()
                .claim("w1").releaseForRetry("e1", BASE_INTERVAL)
                .claim("w2").releaseForRetry("e2", BASE_INTERVAL)
                .claim("w3");

        assertThat(afterThirdClaim.attemptCount()).isEqualTo(3);
        assertThatThrownBy(() -> afterThirdClaim.releaseForRetry("e3", BASE_INTERVAL))
                .isInstanceOf(MaxRetriesExceededException.class);
    }

    @Test
    void releaseForRetry_whenBaseIntervalZero_throwsIllegalArgument() {
        PendingInvocation processing = pending().claim("worker-1");

        assertThatThrownBy(() -> processing.releaseForRetry("x", 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseIntervalSeconds");
    }

    @Test
    void releaseForRetry_whenBaseIntervalNegative_throwsIllegalArgument() {
        PendingInvocation processing = pending().claim("worker-1");

        assertThatThrownBy(() -> processing.releaseForRetry("x", -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseIntervalSeconds");
    }

    @Test
    void fail_whenProcessing_transitionsToFailed() {
        PendingInvocation failed = pending().claim("worker-1").fail("401 unauthorized");

        assertThat(failed.status()).isEqualTo(PendingInvocationStatus.FAILED);
        assertThat(failed.completedAt()).isNotNull();
        assertThat(failed.lastError()).isEqualTo("401 unauthorized");
        assertThat(failed.nextAttemptAt()).isNull();
    }

    @Test
    void fail_whenBlankError_throwsIllegalArgument() {
        PendingInvocation processing = pending().claim("worker-1");

        assertThatThrownBy(() -> processing.fail(" "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("errorMessage");
    }

    @Test
    void fail_whenNotProcessing_throwsInvalidTransition() {
        assertThatThrownBy(() -> pending().fail("x"))
                .isInstanceOf(InvalidPendingInvocationTransitionException.class);
    }

    @Test
    void abandon_whenProcessing_transitionsToAbandoned() {
        PendingInvocation abandoned = pending().claim("worker-1").abandon("gave up after retries");

        assertThat(abandoned.status()).isEqualTo(PendingInvocationStatus.ABANDONED);
        assertThat(abandoned.completedAt()).isNotNull();
        assertThat(abandoned.lastError()).isEqualTo("gave up after retries");
        assertThat(abandoned.nextAttemptAt()).isNull();
    }

    @Test
    void abandon_whenNotProcessing_throwsInvalidTransition() {
        assertThatThrownBy(() -> pending().abandon("x"))
                .isInstanceOf(InvalidPendingInvocationTransitionException.class);
    }

    @Test
    void completedState_isAbsorbing() {
        PendingInvocation completed = pending().claim("worker-1").complete();

        assertThatThrownBy(completed::complete)
                .isInstanceOf(InvalidPendingInvocationTransitionException.class);
        assertThatThrownBy(() -> completed.fail("x"))
                .isInstanceOf(InvalidPendingInvocationTransitionException.class);
        assertThatThrownBy(() -> completed.abandon("x"))
                .isInstanceOf(InvalidPendingInvocationTransitionException.class);
    }

    @Test
    void transition_doesNotMutateOriginalInstance() {
        PendingInvocation original = pending();

        original.claim("worker-1");

        assertThat(original.status()).isEqualTo(PendingInvocationStatus.PENDING);
        assertThat(original.attemptCount()).isZero();
        assertThat(original.claimedBy()).isNull();
        assertThat(original.claimedAt()).isNull();
    }

    @Test
    void fail_whenErrorExceeds1000Chars_truncatesTo1000() {
        String longError = "x".repeat(1500);

        PendingInvocation failed = pending().claim("worker-1").fail(longError);

        assertThat(failed.lastError()).hasSize(1000);
    }

    @Test
    void claim_afterReleaseForRetry_clearsNextAttemptAt() {
        PendingInvocation reClaimed = pending()
                .claim("w1")
                .releaseForRetry("transient", BASE_INTERVAL)
                .claim("w2");

        assertThat(reClaimed.nextAttemptAt()).isNull();
    }

    @Test
    void equalsAndHashCode_areBasedOnId() {
        PendingInvocation invocation = pending();
        PendingInvocation sameIdDifferentState = invocation.claim("worker-1");

        assertThat(sameIdDifferentState).isEqualTo(invocation);
        assertThat(sameIdDifferentState).hasSameHashCodeAs(invocation);
    }
}
