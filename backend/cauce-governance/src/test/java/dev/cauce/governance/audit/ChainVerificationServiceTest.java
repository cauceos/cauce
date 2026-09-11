package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The two halves of verify-and-record, and the two properties that make them safe: nothing
 * is recorded if verification fails, and nothing is returned if recording fails.
 */
class ChainVerificationServiceTest {

    private final AuditChainVerifier verifier = mock(AuditChainVerifier.class);
    private final ChainVerificationRecorder recorder = mock(ChainVerificationRecorder.class);
    private final ChainVerificationService service =
            new ChainVerificationService(verifier, recorder);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @Test
    void verifyAndRecord_recordsExactlyWhatWasVerified_andReturnsIt() {
        ChainHead anchor = new ChainHead(3, "a".repeat(64));
        ChainVerificationResult result = ChainVerificationResult.valid(3, 0);
        when(verifier.verifyChain(tenantId, anchor)).thenReturn(result);

        ChainVerificationResult returned = service.verifyAndRecord(tenantId, anchor, actorId);

        assertThat(returned).isSameAs(result);
        verify(recorder).record(tenantId, actorId, result);
    }

    /** Half one fails: half two never starts. Nothing was written. */
    @Test
    void verifyAndRecord_verificationFails_recordsNothing() {
        when(verifier.verifyChain(eq(tenantId), any()))
                .thenThrow(new IllegalStateException("database away"));

        assertThatThrownBy(() -> service.verifyAndRecord(tenantId, null, actorId))
                .isInstanceOf(IllegalStateException.class);

        verify(recorder, never()).record(any(), any(), any());
    }

    /** Half two fails: the call fails. A verdict this design could not record is not returned. */
    @Test
    void verifyAndRecord_recordingFails_propagatesInsteadOfReturningTheVerdict() {
        when(verifier.verifyChain(tenantId, null)).thenReturn(ChainVerificationResult.valid(3, 0));
        doThrow(new IllegalStateException("outbox insert failed"))
                .when(recorder).record(eq(tenantId), eq(actorId), any());

        assertThatThrownBy(() -> service.verifyAndRecord(tenantId, null, actorId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox insert failed");
    }

    @Test
    void verifyAndRecord_requiresTenantAndActor() {
        assertThatThrownBy(() -> service.verifyAndRecord(null, null, actorId))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.verifyAndRecord(tenantId, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * ON DEMAND ONLY (ADR 0003 §5). The recording path must never be reachable from the
     * background drainer or from any scheduled method in the module. This pins the rule
     * mechanically: the drainer does not even depend on the service, and nothing in the audit
     * package that is scheduled takes it as a collaborator.
     */
    @Test
    void recordingPath_isNotWiredIntoTheDrainerOrAnyScheduledBean() {
        for (Constructor<?> constructor : AuditOutboxDrainer.class.getConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertThat(parameter)
                        .isNotEqualTo(ChainVerificationService.class)
                        .isNotEqualTo(ChainVerificationRecorder.class)
                        .isNotEqualTo(AuditChainVerifier.class);
            }
        }
        for (Constructor<?> constructor : AuditOutboxDrainService.class.getConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertThat(parameter)
                        .isNotEqualTo(ChainVerificationService.class)
                        .isNotEqualTo(ChainVerificationRecorder.class);
            }
        }
        for (var method : ChainVerificationService.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(Scheduled.class))
                    .as("%s must never be scheduled", method.getName()).isFalse();
        }
        for (var method : ChainVerificationRecorder.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(Scheduled.class))
                    .as("%s must never be scheduled", method.getName()).isFalse();
        }
    }
}
