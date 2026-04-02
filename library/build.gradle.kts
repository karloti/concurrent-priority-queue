/*
 * Copyright 2026 Kaloyan Karaivanov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
//    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.karloti"
version = "1.3.6"

val isCodespace = System.getenv("CODESPACES") == "true"
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null || File(rootDir, "local.properties").exists()
val shouldConfigureAndroid = hasAndroidSdk && !isCodespace

if (shouldConfigureAndroid) {
    apply(plugin = libs.plugins.android.kotlin.multiplatform.library.get().pluginId)
    apply(from = "android-setup.gradle")
} else {
    println("⚠️ Android SDK not found. Skipping Android target configuration.")
}

kotlin {
    // JVM
    jvm()

    // iOS
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // tvOS
    tvosX64()
    tvosArm64()
    tvosSimulatorArm64()

    // watchOS
    watchosArm32()
    watchosArm64()
    watchosDeviceArm64()
    watchosX64()
    watchosSimulatorArm64()

    // macOS
    macosX64()
    macosArm64()

    // Linux
    linuxX64()
    linuxArm64()

    // Windows
    mingwX64()

    // Android Native
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    // JavaScript
    js {
        browser()
        binaries.executable()
    }

    // WebAssembly
    wasmJs {
        // To build distributions for and run tests use one or several of:
        browser()
        nodejs()
    }
    wasmWasi {
        // To build distributions for and run tests use one or several of:
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.collections.immutable)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    signAllPublications()

    coordinates(group.toString(), "concurrent-priority-queue", version.toString())

    pom {
        name = "Concurrent Priority Queue"
        description = "A high-performance, lock-free, asynchronous Concurrent Priority Queue for Kotlin Multiplatform."
        inceptionYear = "2026"
        url = "https://github.com/karloti/concurrent-priority-queue"
        licenses {
            license {
                name = "Apache License Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "karloti"
                name = "Kaloyan Karaivanov"
                url = "https://github.com/karloti"
            }
        }
        scm {
            url = "https://github.com/karloti/concurrent-priority-queue"
            connection = "scm:git:git://github.com/karloti/concurrent-priority-queue.git"
            developerConnection = "scm:git:ssh://github.com/karloti/concurrent-priority-queue.git"
        }
    }
}

/*
tasks.register("createMissingSourceDirs") {
    group = "setup"
    description = "Creates all missing source set directories for Kotlin Multiplatform"

    doLast {
        kotlin.sourceSets.forEach { sourceSet ->
            sourceSet.kotlin.srcDirs.forEach { dir ->
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
        }
    }
}
*/

