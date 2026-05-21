// cauce-core — domain model, agent runtime, conversation state, and the plugin SPI.
//
// Pure hexagonal domain core: no JPA, no PostgreSQL, no Flyway, no provider deps.
// The only third-party dependency is a UUIDv7 generator used to mint domain ids.
// Build configuration is inherited from the root project (see ../build.gradle.kts).
plugins {
    java
}

dependencies {
    implementation(libs.uuid.creator)
}
