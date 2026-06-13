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
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // Jackson backs Hibernate's JSON format mapper used to (de)serialize the messages
    // tool_content jsonb column to/from a Map (see MessageEntity).
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Flyway 11.x: PostgreSQL support is a separate module since Flyway 10.
    runtimeOnly("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
