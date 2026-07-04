// cauce-channels — channel adapter SPI and reference adapters.
//
// Defines the channel contract (invariant 3): the inbound half (provider webhook payload →
// neutral ChannelInboundMessage → the existing ingest boundary) and the outbound half
// (neutral ChannelOutboundMessage → provider delivery; interface only until the outbound
// commit). ChannelConfig — the binding of a channel instance to an agent/tenant — lives
// here as a vertical slice (domain + persistence + services), mirroring PendingInvocation
// in cauce-orchestration. The web surface (webhook endpoint, config REST) stays in
// cauce-api: this module is deliberately not web-aware.
//
// Build configuration is inherited from the root project (see ../build.gradle.kts).
plugins {
    java
}

dependencies {
    implementation(project(":cauce-core"))
    implementation(project(":cauce-memory"))
    // The inbound half funnels every channel into the single ingest boundary
    // (InboundMessageService); channels never talk to conversations/messages directly.
    implementation(project(":cauce-orchestration"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Adapters parse provider payloads (Telegram Update JSON).
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // ITs seed the tenant->agent hierarchy through the tenancy services (implementation
    // deps are not transitive at compile time, so orchestration's tenancy does not leak here).
    testImplementation(project(":cauce-tenancy"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
