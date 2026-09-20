plugins {
    kotlin("jvm")
}

group = "dev.zocomputer"
version = "0.2.0"

repositories { mavenCentral() }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

tasks.test {
    testLogging { events("passed", "failed") }
}
