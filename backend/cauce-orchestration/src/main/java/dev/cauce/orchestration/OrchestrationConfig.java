package dev.cauce.orchestration;

import dev.cauce.orchestration.context.ContextBuilder;
import dev.cauce.orchestration.worker.PendingInvocationWorkerProperties;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Module configuration for cauce-orchestration beans.
 *
 * <p>{@link ContextBuilder} is deliberately a framework-free, pure-logic class, so it is
 * exposed as a Spring bean here (rather than annotated {@code @Component}) to keep it
 * injectable into {@code OrchestratorService} while remaining decoupled from Spring.
 *
 * <p>{@link EnableScheduling} powers the {@code @Scheduled} methods of the async worker
 * and the reaper. Each scheduled bean carries its own {@code @ConditionalOnProperty}, so
 * scheduling can be enabled at the framework level while individual jobs stay opt-out (in
 * particular for integration tests of this module other than the worker IT).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(PendingInvocationWorkerProperties.class)
public class OrchestrationConfig {

    /** Bean name of the executor that runs individual pending-invocation processings. */
    public static final String WORKER_EXECUTOR_BEAN = "pendingInvocationWorkerExecutor";

    @Bean
    public ContextBuilder contextBuilder() {
        return new ContextBuilder();
    }

    /**
     * Executor used by the async worker to process one claimed invocation per task.
     *
     * <p>Sized from {@link PendingInvocationWorkerProperties#getThreadPoolSize()}; queue
     * capacity is a modest multiple of the batch size so a single batch always fits.
     * {@link ThreadPoolExecutor.CallerRunsPolicy} provides natural backpressure: if both
     * the pool and the queue are saturated, the scheduler thread runs the task itself,
     * which slows the next poll and gives the pool time to drain.
     */
    @Bean(name = WORKER_EXECUTOR_BEAN)
    public TaskExecutor pendingInvocationWorkerExecutor(PendingInvocationWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getThreadPoolSize());
        executor.setMaxPoolSize(properties.getThreadPoolSize());
        executor.setQueueCapacity(Math.max(properties.getBatchSize() * 4, 8));
        executor.setThreadNamePrefix("pi-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
