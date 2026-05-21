// cauce-tenancy — multi-tenant isolation, hierarchical visibility, quotas,
// usage tracking.
//
// Application-service layer for tenancy. Depends on cauce-core (domain) and
// cauce-memory (persistence). spring-boot-starter-data-jpa is needed to compile
// against TenantRepository (a JpaRepository) and provides spring-tx/spring-context
// for @Transactional/@Service. Build config inherited from the root project.
plugins {
    java
}

dependencies {
    implementation(project(":cauce-core"))
    implementation(project(":cauce-memory"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
