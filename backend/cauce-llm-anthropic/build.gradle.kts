// cauce-llm-anthropic — Anthropic Claude adapter for the cauce-llm SPI.
//
// Implements LlmProvider against the Anthropic Messages API using the JDK HttpClient
// (java.net.http) and Jackson. No third-party HTTP client or vendor SDK. Spring is used
// only for bean wiring/configuration of the adapter.
// Build configuration is inherited from the root project (see ../build.gradle.kts).
plugins {
    java
}

dependencies {
    implementation(project(":cauce-llm"))
    // The neutral tool model (ToolDefinition/ToolCall/ToolResult) this adapter maps to/from the
    // Anthropic tool wire format lives in cauce-core; it appears in the cauce-llm contract.
    implementation(project(":cauce-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // @Configuration, @Bean, @ConfigurationProperties, @ConditionalOnProperty.
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation(libs.wiremock)
}
