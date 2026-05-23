package dev.cauce.llm.spi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SystemDefaultLlmCredentialTest {

    @Test
    void getApiKey_readsProviderSpecificEnvVar() {
        MockEnvironment env = new MockEnvironment().withProperty("ANTHROPIC_API_KEY", "sk-ant-123");

        SystemDefaultLlmCredential credential = new SystemDefaultLlmCredential("anthropic", env);

        assertThat(credential.getApiKey()).isEqualTo("sk-ant-123");
    }

    @Test
    void getApiKey_isNullWhenVarAbsent() {
        SystemDefaultLlmCredential credential =
                new SystemDefaultLlmCredential("anthropic", new MockEnvironment());

        assertThat(credential.getApiKey()).isNull();
    }

    @Test
    void getOrganizationId_emptyWhenAbsent() {
        SystemDefaultLlmCredential credential =
                new SystemDefaultLlmCredential("anthropic", new MockEnvironment());

        assertThat(credential.getOrganizationId()).isEmpty();
    }

    @Test
    void getOrganizationId_presentWhenSet() {
        MockEnvironment env = new MockEnvironment().withProperty("OPENAI_ORG_ID", "org-42");

        SystemDefaultLlmCredential credential = new SystemDefaultLlmCredential("openai", env);

        assertThat(credential.getOrganizationId()).contains("org-42");
    }

    @Test
    void providerId_isUppercasedForVarName() {
        MockEnvironment env = new MockEnvironment().withProperty("ANTHROPIC_API_KEY", "sk");

        // lower-case provider id resolves the upper-case env var
        assertThat(new SystemDefaultLlmCredential("anthropic", env).getApiKey()).isEqualTo("sk");
    }
}
