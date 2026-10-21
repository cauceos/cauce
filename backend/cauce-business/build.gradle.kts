// cauce-business — native business capabilities (ADR 0007).
//
// The native adapters of the business ports defined in cauce-core (profile, catalog, bookings,
// handoffs, unanswered questions), with their own domain, persistence and services, plus the
// owner-notification outbox and the daily digest. The tools that expose these capabilities to
// an agent live in cauce-tools and depend only on the core ports, so an external system can
// replace a native adapter without touching the agent. Sector knowledge is data, never code.
//
// Build configuration is inherited from the root project (see ../build.gradle.kts).
plugins {
    java
}

dependencies {
    implementation(project(":cauce-core"))
    implementation(project(":cauce-memory"))
    // Owner binding resolves the owner's identity through the tenancy services.
    implementation(project(":cauce-tenancy"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Admin and conduct audit records go through the core port; the governance adapter is
    // test-scope so ITs boot with the real recorder (production wiring is in cauce-api).
    testImplementation(project(":cauce-governance"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
