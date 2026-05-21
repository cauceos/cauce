// cauce-memory — persistent state, cross-channel identity, and vector retrieval.
//
// Owns the JPA mapping and Flyway migrations for domain types defined in
// cauce-core. This is where persistence (JPA, PostgreSQL, Flyway) lives, keeping
// cauce-core free of infrastructure. Build config inherited from the root project.
plugins {
    java
}

dependencies {
    implementation(project(":cauce-core"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Flyway 11.x: PostgreSQL support is a separate module since Flyway 10.
    runtimeOnly("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
