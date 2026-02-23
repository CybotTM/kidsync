plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("io.ktor.plugin") version "3.0.3"
    id("org.jetbrains.kotlinx.kover") version "0.9.7"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "dev.kidsync"
version = "0.1.0"

application {
    mainClass.set("dev.kidsync.server.ApplicationKt")
}

repositories {
    mavenCentral()
}

// SEC2-S-10: Dependency versions should be periodically reviewed for security updates.
// Run: ./gradlew dependencyUpdates (with com.github.ben-manes.versions plugin)
// or check manually at https://github.com/advisories
val ktorVersion = "3.0.3"
val exposedVersion = "0.57.0"
val logbackVersion = "1.5.15"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-rate-limit:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")

    // Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // kotlinx.serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Cryptography (Ed25519 signature verification)
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

ktor {
    docker {
        jreVersion.set(JavaVersion.VERSION_21)
        localImageName.set("kidsync-server")
        imageTag.set(version.toString())
    }
}

kover {
    reports {
        filters {
            excludes {
                // Exclude generated code and the main application entry point
                classes("dev.kidsync.server.ApplicationKt")
            }
        }

        verify {
            rule("Minimum line coverage") {
                minBound(60)
            }
        }
    }
}

detekt {
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        sarif.required.set(true)
        html.required.set(true)
        xml.required.set(false)
    }
}
