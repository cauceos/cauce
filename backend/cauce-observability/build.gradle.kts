// cauce-observability — OpenTelemetry integration, traces, metrics, replay.
//
// First real content: Micrometer metrics derived from the orchestration event stream.
// Depends only on the zero-dep event contract (never on cauce-orchestration internals),
// spring-context for the @EventListener component, and the micrometer-core facade —
// exporters (OTLP/Prometheus) are deliberately not configured yet; the application
// module's Actuator wires the MeterRegistry.
//
// Build configuration is inherited from the root project (see ../build.gradle.kts).
plugins {
    java
}

dependencies {
    implementation(project(":cauce-orchestration-events"))
    implementation("org.springframework:spring-context")
    implementation("io.micrometer:micrometer-core")
    implementation("org.slf4j:slf4j-api")
}
