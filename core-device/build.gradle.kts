plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

// Photo-style contact sheets (PhotoStyleSheets) are written only with -Pscreens=<folder>, like the app's screenshots.
tasks.withType<Test>().configureEach {
    (project.findProperty("screens") as String?)?.let { systemProperty("screens.out", it) }
    systemProperty("java.awt.headless", "true")
}
