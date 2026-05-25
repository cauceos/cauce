// cauce-orchestration — the asynchronous LLM invocation queue and (in later commits)
// the worker and synchronous orchestrator that drain it.
//
// Application-service layer for orchestration. Main code depends only on cauce-core
// (domain) and cauce-memory (persistence repositories it reads to resolve the owning
// tenant); spring-boot-starter-data-jpa is needed to compile against the Spring Data
// repositories and provides spring-tx/spring-context for @Transactional/@Service.
//
// cauce-tenancy is test-only: the integration test seeds the tenant->agent->conversation
// ->message graph through the existing services. cauce-llm is intentionally NOT a
// dependency yet — it lands when the synchronous orchestrator that calls providers does.
// Build config inherited from the root project (see ../build.gradle.kts).
plugins {
    java
}

dependencies {
    implementation(project(":cauce-core"))
    implementation(project(":cauce-memory"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation(project(":cauce-tenancy"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
