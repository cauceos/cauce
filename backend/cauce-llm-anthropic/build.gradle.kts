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
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // @Configuration, @Bean, @ConfigurationProperties, @ConditionalOnProperty.
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation(libs.wiremock)
}
