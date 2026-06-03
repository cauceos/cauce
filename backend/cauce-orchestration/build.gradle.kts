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
// cauce-tenancy is a main dependency: InboundMessageService (the inbound ingest unit) composes
// the tenancy application services (Conversation/Message) with the orchestration queue in one
// transaction. Tests also seed the tenant->agent->conversation->message graph through them.
// Build config inherited from the root project (see ../build.gradle.kts).
plugins {
    java
}

dependencies {
    implementation(project(":cauce-core"))
    implementation(project(":cauce-memory"))
    implementation(project(":cauce-llm"))
    implementation(project(":cauce-tenancy"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
