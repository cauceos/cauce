// cauce-governance — immutable audit log, RGPD endpoints, policy engine,
// AI Act compliance.
//
// First content: the guaranteed-capture skeleton of the audit trail — the transactional
// outbox (written in the caller's business tx via AuditEventRecorder), the append-only
// audit_log_entries ledger (UPDATE/DELETE revoked from cauce_app at the DB grant layer),
// and the per-tenant background drainer. Depends only on cauce-core (domain, TenantContext)
// and cauce-memory (RlsContextAspect at runtime; Flyway migrations live there);
// spring-boot-starter-data-jpa compiles the module's own entities/repositories.
// cauce-tenancy is test-only (IT hierarchy seeding). Build config inherited from the root
// project.
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
