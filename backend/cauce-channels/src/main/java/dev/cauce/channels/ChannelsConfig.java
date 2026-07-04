package dev.cauce.channels;

import dev.cauce.channels.telegram.TelegramChannelAdapter;
import dev.cauce.channels.telegram.TelegramProperties;
import dev.cauce.core.apikey.ApiKeyHasher;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Module beans for cauce-channels: the Telegram adapter (built with its HTTP client and
 * properties, mirroring the LLM adapter configurations) and the dedicated executor for
 * outbound deliveries.
 */
@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class ChannelsConfig {

    @Bean
    public TelegramChannelAdapter telegramChannelAdapter(ApiKeyHasher hasher,
                                                         TelegramProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .build();
        return new TelegramChannelAdapter(hasher, httpClient, properties);
    }

    /**
     * Dedicated pool for outbound channel deliveries (mirror of the invocation worker's
     * executor): network I/O to a provider must never run on — or block — the invocation
     * processing threads. Small pool, unbounded queue: a slow or down provider queues
     * best-effort deliveries instead of rejecting them, bounded in time by the adapter's
     * HTTP timeouts.
     */
    @Bean
    public TaskExecutor channelOutboundExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("channel-outbound-");
        executor.initialize();
        return executor;
    }
}
