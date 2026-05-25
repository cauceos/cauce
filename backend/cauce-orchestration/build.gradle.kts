// cauce-orchestration — the asynchronous LLM invocation queue and (in later commits)
// the worker and synchronous orchestrator that drain it.
//
// Application-service layer for orchestration. Main code depends on cauce-core (domain),
// cauce-memory (persistence repositories it reads to resolve the owning tenant), and
// cauce-llm (the neutral message model — ContextBuilder maps Cauce messages to LlmMessage
// while honouring the model's context window); spring-boot-starter-data-jpa is needed to
// compile against the Spring Data repositories and provides spring-tx/spring-context for
// @Transactional/@Service.
//
// The cauce-llm dependency is the SPI's neutral model only (LlmMessage/LlmRole); no
// provider is invoked here — that lands with the synchronous orchestrator.
//
// cauce-tenancy is test-only: the integration test seeds the tenant->agent->conversation
// ->message graph through the existing services.
// Build config inherited from the root project (see ../build.gradle.kts).
plugins {
    java
}

dependencies {
    implementation(project(":cauce-core"))
    implementation(project(":cauce-memory"))
    implementation(project(":cauce-llm"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation(project(":cauce-tenancy"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
