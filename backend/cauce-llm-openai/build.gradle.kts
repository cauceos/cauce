// cauce-llm-openai — OpenAI-compatible chat-completions adapter for the cauce-llm SPI.
//
// One LlmProvider implementation speaking the OpenAI /chat/completions wire format, used for
// OpenAI, Mistral, and a locally hosted Ollama (each selected by configuration). Built on the JDK
// HttpClient (java.net.http) and Jackson — no third-party HTTP client or vendor SDK. Spring is used
// only for bean wiring/configuration of the adapter.
// Build configuration is inherited from the root project (see ../build.gradle.kts).
plugins {
    java
}

dependencies {
    implementation(project(":cauce-llm"))
    // The neutral tool model (ToolDefinition/ToolCall/ToolResult) this adapter maps to/from the
    // OpenAI tool wire format lives in cauce-core; it appears in the cauce-llm contract.
    implementation(project(":cauce-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // @Configuration, @Bean, @ConfigurationProperties, @ConditionalOnProperty.
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation(libs.wiremock)
}
