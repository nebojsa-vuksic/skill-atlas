plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "skillatlas"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml.engine)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "skillatlas.MainKt"
    applicationName = "skill-atlas"
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}
