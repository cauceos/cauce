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
    // The published signing-key registry is a JSON file read at startup. Jackson is already
    // on the application runtime classpath (starter-web in cauce-api); this declares it for
    // the module that parses the file. Version managed by the Spring Boot BOM.
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(project(":cauce-tenancy"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}

// Cold start for the audit signing layer: generates an Ed25519 key pair and prints the
// private key plus a ready-to-paste registry entry. See AuditSigningKeygen.
tasks.register<JavaExec>("generateAuditSigningKey") {
    group = "application"
    description = "Generates an Ed25519 audit signing key pair and its registry entry"
    mainClass = "dev.cauce.governance.audit.signing.AuditSigningKeygen"
    classpath = sourceSets["main"].runtimeClasspath
}
