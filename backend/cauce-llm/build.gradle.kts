// cauce-llm — LLM provider SPI.
//
// Defines the provider contract, neutral request/response model, exception hierarchy,
// a Spring-managed provider registry, and environment-based credential resolution.
// Concrete provider adapters live in separate cauce-llm-<provider> modules; this module
// never depends on a specific provider.
//
// spring-context is required for the @Component registry (which collects all LlmProvider
// beans) and for reading credentials from the Spring Environment. See package-info.java.
// Build configuration is inherited from the root project (see ../build.gradle.kts).
plugins {
    java
}

dependencies {
    implementation("org.springframework:spring-context")
}
