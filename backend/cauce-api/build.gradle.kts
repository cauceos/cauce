// cauce-api — REST API surface and the Spring Boot application module.
//
// This is the only module that applies the Spring Boot plugin, producing the
// executable (fat) jar via the bootJar task. It aggregates the BUSL-licensed
// backend modules. It deliberately does NOT depend on cauce-enterprise:
// architectural invariant — BUSL modules never depend on the commercial module.
plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":cauce-core"))
    implementation(project(":cauce-memory"))
    implementation(project(":cauce-channels"))
    implementation(project(":cauce-llm"))
    implementation(project(":cauce-evals"))
    implementation(project(":cauce-observability"))
    implementation(project(":cauce-governance"))
    implementation(project(":cauce-tenancy"))
    implementation(project(":cauce-orchestration"))

    // LLM provider adapters are discovered at runtime via the cauce-llm SPI; the app does
    // not compile against any specific provider (only against the SPI in cauce-llm).
    runtimeOnly(project(":cauce-llm-anthropic"))
    runtimeOnly(project(":cauce-llm-openai"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    runtimeOnly("org.postgresql:postgresql")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}
