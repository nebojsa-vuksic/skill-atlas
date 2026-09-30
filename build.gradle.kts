plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    application
}

group = "skillatlas"
version = "0.1.0"

repositories {
    mavenCentral()
    // Mosaic's Compose runtime depends on AndroidX artifacts that are only published here.
    google()
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml.engine)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.mosaic.runtime)
    implementation(libs.mosaic.tty)

    testImplementation(kotlin("test"))
    testImplementation(libs.mosaic.testing)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "skillatlas.MainKt"
    applicationName = "skill-atlas"
    // Mosaic calls into its native terminal library through JNI or, on Java 22+, the FFM API.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

// Black-box tests that run the installed CLI as a separate process (spec section 11.2).
testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                // The Compose compiler plugin applies to every compilation and insists on its runtime.
                compileOnly(libs.mosaic.runtime)
            }
            targets.all {
                testTask.configure {
                    val installDist = tasks.installDist
                    dependsOn(installDist)
                    inputs.dir(installDist.map { it.destinationDir })
                    systemProperty(
                        "skillAtlas.launcher",
                        installDist.get().destinationDir.resolve("bin/skill-atlas").absolutePath,
                    )
                    systemProperty("skillAtlas.version", project.version.toString())
                    shouldRunAfter(tasks.test)
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("integrationTest"))
}
