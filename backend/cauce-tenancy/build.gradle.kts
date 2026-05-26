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
    // BCryptPasswordEncoder for the BcryptApiKeyHasher adapter. The minimal
    // spring-security-crypto subset (no servlet/web filter chain) keeps cauce-tenancy
    // framework-light; the full spring-boot-starter-security lives in cauce-api.
    implementation("org.springframework.security:spring-security-crypto")
    // In-process cache for successful ApiKey verifications. Bcrypt is intentionally
    // slow (~100ms at cost 10); a 5-minute TTL cache amortises that across requests.
    implementation("com.github.ben-manes.caffeine:caffeine")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.postgresql:postgresql")
}
