package dev.cauce.evals.run;

import dev.cauce.core.business.owner.OwnerNotifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * What a run needs around the application: Redis (the health indicator the app aggregates), and an
 * owner notifier that records what it would send instead of calling a channel. The notice itself
 * is still composed, queued and dispatched by the real code; only the provider call is replaced, so
 * no run ever messages a real owner. In a rehearsal it also registers {@link RehearsalProvider}.
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
