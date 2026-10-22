plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

repositories { mavenCentral() }

dependencies {
    // api, not implementation: the :app module consumes CatalogEntry, Hashes and
    // SourceConfig, and those types reference okhttp/serialization in their
    // signatures.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("com.squareup.okhttp3:okhttp:5.2.1")
    api("org.jsoup:jsoup:1.21.2")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

application { mainClass.set("caddy.MainKt") }

tasks.test { useJUnitPlatform() }
