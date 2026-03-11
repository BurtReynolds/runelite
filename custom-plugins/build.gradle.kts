plugins {
    java
}

// Disable dependency verification for custom plugins
tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-options")
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "java-library")

    group = "com.custom.runelite"
    version = "1.0.0"

    java {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    repositories {
        mavenCentral()
        maven {
            url = uri("https://repo.runelite.net")
        }
        maven {
            url = uri("https://raw.githubusercontent.com/runelite/maven-repo/master")
        }
    }

    dependencies {
        // RuneLite dependencies (compileOnly because they're provided by the client)
        compileOnly("net.runelite:runelite-api:1.12.20-SNAPSHOT")

        // Common dependencies
        compileOnly("com.google.inject:guice:5.1.0")
        compileOnly("org.slf4j:slf4j-api:1.7.36")
        compileOnly("com.google.code.findbugs:jsr305:3.0.2")

        // Lombok for code generation
        compileOnly("org.projectlombok:lombok:1.18.30")
        annotationProcessor("org.projectlombok:lombok:1.18.30")

        // Testing
        testImplementation("junit:junit:4.13.2")
        testImplementation("org.mockito:mockito-core:5.7.0")
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:deprecation")
    }
}
