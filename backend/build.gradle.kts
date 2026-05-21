import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    // Declared (but not applied) at the root so the plugin classes are on the
    // build classpath. Individual modules opt in: the Spring Boot plugin is
    // applied only by the application module (cauce-api).
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "dev.cauce"
    version = "0.0.1-SNAPSHOT"
}

// Configuration shared by every backend module.
subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    // Import the Spring Boot BOM so module build files can declare dependencies
    // without versions; the BOM keeps the whole platform aligned.
    configure<DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
        }
    }

    dependencies {
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        // Aligns the JUnit Platform launcher with the engine version pinned by the
        // Spring Boot BOM; without it Gradle injects a mismatched launcher.
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // Spring relies on parameter name retention for binding and DI.
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
