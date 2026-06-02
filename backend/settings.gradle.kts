plugins {
    // Lets Gradle auto-provision the Java 21 toolchain if it is not already
    // installed on the machine. Compatible with the Gradle 8.x line.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "cauce"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

// Backend multi-module project. The Angular frontend lives separately under
// ../frontend and is not part of this Gradle build.
include(
    "cauce-core",
    "cauce-memory",
    "cauce-channels",
    "cauce-llm",
    "cauce-llm-anthropic",
    "cauce-llm-openai",
    "cauce-evals",
    "cauce-observability",
    "cauce-governance",
    "cauce-tenancy",
    "cauce-orchestration",
    "cauce-api",
    "cauce-enterprise",
)
