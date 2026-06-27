import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    `maven-publish`
}

group = "com.ospchat"
version = file("VERSION").readText().trim()

kotlin {
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    androidTarget {
        publishLibraryVariants("release")
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            // `api` so consumers (Android app, desktop app) see RoomDatabase /
            // DataStore types in the public API surface of our repositories.
            api(libs.androidx.datastore.preferences.core)
            api(libs.androidx.room.runtime)
            api(libs.androidx.sqlite.bundled)
            api(libs.ktor.server.core)
            api(libs.ktor.client.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.status.pages)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val desktopTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinxCoroutines.get()}")
                implementation(libs.ktor.server.test.host)
                implementation(libs.ktor.client.mock)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.jmdns)
                // Bouncy Castle for Ed25519. JDK 17 has Ed25519 in
                // java.security, but Android API 26-32 (our floor) does not,
                // so we use BC on both JVM and Android for one code path.
                implementation(libs.bouncycastle)
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.exifinterface)
            implementation(libs.bouncycastle)
        }
    }
}

android {
    namespace = "com.ospchat.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}

// --- Publishing ---------------------------------------------------------------
//
// Local only. `publishToMavenLocal` (a.k.a. `make publish-local`) populates
// ~/.m2/repository so `ospchat-desktop` can consume the artifact; `ospchat-android`
// builds this module from source via a Gradle composite build. There is no remote
// Maven repository — consumers never fetch a prebuilt binary, which also keeps the
// build reproducible-from-source for F-Droid. The `maven-publish` plugin alone
// gives the Kotlin Multiplatform publications + the `publishToMavenLocal` task; no
// `publishing {}` repositories block is needed.
