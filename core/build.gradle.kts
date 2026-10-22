plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.2.1")
    implementation("org.jsoup:jsoup:1.21.2")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

application { mainClass.set("caddy.MainKt") }

tasks.test { useJUnitPlatform() }
