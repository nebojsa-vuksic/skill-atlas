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
