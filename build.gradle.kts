plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "dev.zocomputer"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test-junit5"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.zocomputer.mcp.DemoKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
    }
}
