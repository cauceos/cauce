package dev.cauce.orchestration.support;

import dev.cauce.orchestration.service.MockLlmProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The SHARED mock-provider configuration for orchestration ITs. Every IT that needs a
 * scriptable LLM provider imports THIS class (never a nested per-IT copy): with identical
 * context configuration, Spring's test-context cache serves all of them from ONE
 * ApplicationContext — one datasource pool pair instead of one per IT. Per-IT nested
 * configs multiplied cached contexts until the shared Postgres container ran out of
 * connections ("sorry, too many clients already").
 *
 * <p>The bean is stateful but scriptable per test: each IT stubs
 * {@link MockLlmProvider#respondWith} in its own setup.
 */
@TestConfiguration
public class MockLlmProviderTestConfig {

    @Bean
    MockLlmProvider mockLlmProvider() {
        return new MockLlmProvider();
    }
}
