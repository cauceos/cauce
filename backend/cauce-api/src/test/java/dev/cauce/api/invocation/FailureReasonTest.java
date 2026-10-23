package dev.cauce.api.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.orchestration.events.InvocationFailureType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FailureReasonTest {

    @ParameterizedTest
    @EnumSource(InvocationFailureType.class)
    void from_mapsEveryInternalFailureType(InvocationFailureType type) {
        assertThat(FailureReason.from(type)).isNotNull();
    }

    @Test
    void from_spendLimitReached_isItsOwnPublicReason() {
        assertThat(FailureReason.from(InvocationFailureType.SPEND_LIMIT_REACHED))
                .isEqualTo(FailureReason.SPEND_LIMIT_REACHED);
    }

    @Test
    void from_emptyReply_isItsOwnPublicReason() {
        assertThat(FailureReason.from(InvocationFailureType.EMPTY_REPLY)).isEqualTo(FailureReason.EMPTY_REPLY);
    }
}
