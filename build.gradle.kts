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
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.exifinterface)
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
// `publishToMavenLocal` populates ~/.m2/repository for local dev (the desktop
// app's primary consumption path right now).
//
// `publish` pushes to GitHub Packages on tag-push releases. CI sets the two
// project properties `gprUser` + `gprToken` via env vars matching Gradle's
// `ORG_GRADLE_PROJECT_*` convention. For local publishing to a real registry
// you can also set them in `~/.gradle/gradle.properties`.
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            // GITHUB_REPOSITORY is set by GitHub Actions; outside CI we
            // default to a sensible placeholder so configuration still works
            // (the repo just won't be reachable for actual publish).
            val ghRepo = System.getenv("GITHUB_REPOSITORY") ?: "OWNER/ospchat-shared"
            url = uri("https://maven.pkg.github.com/$ghRepo")
            credentials {
                username = (findProperty("gprUser") as String?) ?: System.getenv("GITHUB_ACTOR")
                password = (findProperty("gprToken") as String?) ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
