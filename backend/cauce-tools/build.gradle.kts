// cauce-tools — executable tool SPI and built-in tools.
//
// Defines the Tool contract every tool implements and a Spring-managed registry that
// collects all Tool beans, mirroring the cauce-llm provider SPI. The neutral tool model
// (definition, call, result) lives in cauce-core; this module depends only on core (plus
// spring-context for the @Component registry), never on cauce-llm or any provider.
plugins {
    java
}

dependencies {
    implementation(project(":cauce-core"))
    implementation("org.springframework:spring-context")
}
