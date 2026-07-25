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
    // Invocation lifecycle event contract (leaf module, zero deps): the loop, worker,
    // reaper, and ingest publish these records via ApplicationEventPublisher.
    implementation(project(":cauce-orchestration-events"))
    implementation(project(":cauce-memory"))
    implementation(project(":cauce-llm"))
    // The agentic loop injects the ToolRegistry (all registered tools are offered to every
    // agent) and dispatches tool calls through it.
    implementation(project(":cauce-tools"))
    implementation(project(":cauce-tenancy"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Main code emits audit events through the cauce-core AuditEventRecorder port only;
    // the outbox adapter (cauce-governance) is test-scope so ITs boot with the real
    // recorder and can drain/verify the actual audit chain. Production wiring lives in
    // cauce-api, which depends on both.
    testImplementation(project(":cauce-governance"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
