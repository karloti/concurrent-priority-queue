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
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}
group = "io.github.karloti"
version = "1.0.0"

kotlin {
    // JVM
    jvm()

    // Android
    android {
        namespace = "io.github.karloti.cpq"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilations.configureEach {
            compileTaskProvider.configure {
            }
        }
    }

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
        d8()
    }
    wasmWasi {
        // To build distributions for and run tests use one or several of:
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            //put your multiplatform dependencies here
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.atomicfu)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "library", version.toString())

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
