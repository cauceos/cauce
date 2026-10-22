// cauce-evals — the eval harness of the sector agents (ADR 0009).
//
// Two halves. The main source set is the harness itself — the scenario format and its validation,
// the checks, the judge, cost, the report and the history — with no dependency on the
// application, so its tests run in the normal build and stay hermetic. The `eval` source set is the
// runner: it boots the cauce-api application against PostgreSQL in Testcontainers with the real
// model and drives the scenarios of examples/<sector>/evals. Only the `eval` task runs it, never
// `check` or `build`, and never CI: it spends real tokens and needs the model's key.
//
// Build configuration is inherited from the root project (see ../build.gradle.kts).
plugins {
    java
}

val eval: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

// The runner uses the harness, so it gets the harness's own dependencies too.
val evalImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.implementation.get())
}
val evalRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.runtimeOnly.get())
}

dependencies {
    implementation(project(":cauce-core"))
    implementation(project(":cauce-llm"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // The runner drives the whole application in-process (ADR 0009, E1).
    evalImplementation(project(":cauce-api"))
    evalImplementation(project(":cauce-core"))
    evalImplementation(project(":cauce-llm"))
    evalImplementation(project(":cauce-business"))
    evalImplementation(project(":cauce-tenancy"))
    evalImplementation(project(":cauce-orchestration"))
    // What a channel delivers is the agent's text rendered for it (ReplyMarkdown).
    evalImplementation(project(":cauce-channels"))
    evalImplementation("com.fasterxml.jackson.core:jackson-databind")
    evalImplementation("org.springframework.boot:spring-boot-starter-test")
    evalImplementation("org.springframework.boot:spring-boot-starter-web")
    evalImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    evalImplementation("org.springframework.boot:spring-boot-testcontainers")
    evalImplementation("org.testcontainers:postgresql")
    evalImplementation("org.testcontainers:junit-jupiter")
    evalRuntimeOnly("org.junit.platform:junit-platform-launcher")
    evalRuntimeOnly("org.postgresql:postgresql")
    evalRuntimeOnly(project(":cauce-llm-anthropic"))
    evalRuntimeOnly(project(":cauce-llm-openai"))
    evalRuntimeOnly(project(":cauce-tools"))
}

// ./gradlew :cauce-evals:eval [-Pevals.tarea=03 | -Pevals.tarea=01,02] [-Pevals.escenarios=H3.10,H3.11] [-Pevals.n=5]
//     [-Pevals.limite=10] [-Pevals.confirmar=true] [-Pevals.juez.proveedor=openai -Pevals.juez.modelo=...]
val evalTask = tasks.register<Test>("eval") {
    description = "Runs the sector agents' scenarios against the real model (ADR 0009). Spends tokens."
    group = "verification"
    testClassesDirs = eval.output.classesDirs
    classpath = eval.runtimeClasspath
    useJUnitPlatform()
    // A run is never up to date: the model is not deterministic and the point is to measure it.
    outputs.upToDateWhen { false }
    // Without the JVM's notices about Mockito's agent and class data sharing.
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
    systemProperty("evals.raiz", rootProject.projectDir.parentFile.absolutePath)
    project.properties.filterKeys { it.startsWith("evals.") }
            .forEach { (key, value) -> systemProperty(key, value.toString()) }
    testLogging {
        showStandardStreams = true
        events("failed")
    }
}
