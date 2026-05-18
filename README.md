# OSPChat — Shared (Kotlin Multiplatform)

Shared Kotlin code consumed by both [`ospchat-android`](https://github.com/tb0hdan/ospchat-android)
and [`ospchat-desktop`](https://github.com/tb0hdan/ospchat-desktop). DTOs, wire protocol, domain
use cases, Room data layer, identity, peer-discovery service, attachment
abstractions — everything that isn't platform-specific lives here.

## Targets

| Target            | Source set         | Consumed by         |
| ----------------- | ------------------ | ------------------- |
| `androidTarget()` | `androidMain`      | `ospchat-android`   |
| `jvm("desktop")`  | `desktopMain`      | `ospchat-desktop`   |
| —                 | `commonMain`       | both                |

## Build

Gradle wrapper is not committed. Bootstrap once with a system Gradle 8.10.2+:

```bash
gradle wrapper --gradle-version 8.10.2
./gradlew build
```

Run common tests:

```bash
./gradlew allTests
```

## How apps consume it

Each app's `settings.gradle.kts` uses a Gradle composite build:

```kotlin
includeBuild("../ospchat-shared")
```

and depends on the artifact:

```kotlin
implementation("com.ospchat:ospchat-shared")
```

Gradle substitutes the included build automatically.

## Layout

```
ospchat-shared/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
└── src/
    ├── commonMain/kotlin/com/ospchat/shared/
    ├── commonTest/kotlin/com/ospchat/shared/
    ├── androidMain/
    │   ├── AndroidManifest.xml
    │   └── kotlin/com/ospchat/shared/
    └── desktopMain/kotlin/com/ospchat/shared/
```
