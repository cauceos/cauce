package dev.cauce.evals.run;

import dev.cauce.core.business.owner.OwnerNotifier;
import dev.cauce.evals.report.EstimateRatios;
import dev.cauce.orchestration.events.LlmResponded;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * What a run needs around the application: Redis (the health indicator the app aggregates), and an
 * owner notifier that records what it would send instead of calling a channel. The notice itself
 * is still composed, queued and dispatched by the real code; only the provider call is replaced, so
 * no run ever messages a real owner. In a rehearsal it also registers {@link RehearsalProvider}.
 * It also keeps each model call's input estimate and bill, which the run writes next to its report.
 */
@TestConfiguration(proxyBeanMethods = false)
class EvalInfrastructure {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }

    /** The rehearsal's model, registered under the agent's provider id in place of the real one. */
    @Bean
    @ConditionalOnProperty(name = "evals.simulacro", havingValue = "true")
    RehearsalProvider rehearsalProvider(@Value("${evals.proveedor:anthropic}") String provider) {
        return new RehearsalProvider(provider);
    }

    @Bean
    @Primary
    RecordingOwnerNotifier recordingOwnerNotifier() {
        return new RecordingOwnerNotifier();
    }

    @Bean
    EstimateRecorder estimateRecorder() {
        return new EstimateRecorder();
    }

    /** Keeps, from every model call the agent made, what the context budget estimated and what was billed. */
    static final class EstimateRecorder {

        final List<EstimateRatios.Call> calls = new CopyOnWriteArrayList<>();

        @EventListener
        void on(LlmResponded responded) {
            calls.add(new EstimateRatios.Call(responded.provider(), responded.modelName(), responded.roundIndex(),
                    responded.estimatedInputTokens(), responded.inputTokens()));
        }
    }

    /** Accepts every notice and keeps its text. */
    static final class RecordingOwnerNotifier implements OwnerNotifier {

        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public Delivery send(Recipient recipient, String text) {
            sent.add(text);
            return Delivery.sent();
        }
    }
}
