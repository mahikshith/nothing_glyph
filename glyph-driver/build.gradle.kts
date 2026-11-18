plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dotforge.driver"
    compileSdk = 36
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core-device"))
    api(libs.kotlinx.coroutines.core)
    // The Nothing SDK is not redistributable: scripts/fetch-glyph-sdk.sh downloads it into app/libs/.
    // Compile-only here; the :app module packages it.
    compileOnly(files("../app/libs/glyph-matrix-sdk-2.0.aar"))
}
