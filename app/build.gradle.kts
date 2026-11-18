plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dotforge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dotforge"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Screenshot tests render every screen with Robolectric; they run only with -Pscreens=<folder>.
            val screens = project.findProperty("screens") as String?
            if (screens == null) test.exclude("**/screens/**")
            else test.systemProperty("screens.out", screens)
            // Robolectric fetches its Android jars itself; ROBOLECTRIC_REPO points it at a Maven mirror if set.
            System.getenv("ROBOLECTRIC_REPO")?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
            test.maxHeapSize = "3g"
        }
    }
    // NOTICE and licenses/ ship in the app (Profile > About > Licences); copied, never duplicated in src/
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/notices"))
    // the CC0 / public-domain test photos live with core-device's tests; the Photo screenshots use them too
    sourceSets["test"].resources.srcDir("../core-device/src/test/resources")
}

val copyNotices by tasks.registering(Sync::class) {
    from(rootProject.file("NOTICE"))
    from(rootProject.file("licenses")) { into("licenses") }
    into(layout.buildDirectory.dir("generated/notices/notices"))
}
tasks.named("preBuild") { dependsOn(copyNotices) }

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core-device"))
    implementation(project(":glyph-driver"))
    implementation(project(":composition"))
    // Nothing Glyph Matrix SDK: fetched by scripts/fetch-glyph-sdk.sh, never committed.
    implementation(files("libs/glyph-matrix-sdk-2.0.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
