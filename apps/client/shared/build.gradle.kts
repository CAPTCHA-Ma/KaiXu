import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library) apply false
}

val desktopOnly = providers.gradleProperty("desktopOnly").orNull.toBoolean()
if (!desktopOnly) {
    // AGP 8.x 的 Android library 与独立 androidApp 配合；不混用 AGP 9 的 DSL。
    apply(plugin = "com.android.library")
}

kotlin {
    jvmToolchain(21)
    jvm()
    if (!desktopOnly) {
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.lifecycle.viewmodel.core)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

dependencies {
    add("kspJvm", libs.room.compiler)
    if (!desktopOnly) add("kspAndroid", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

// 只有启用Android目标时才读取本机SDK和配置Android library。
if (!desktopOnly) {
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "com.kaixu.shared"
        compileSdk = 36
        defaultConfig { minSdk = 29 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}

