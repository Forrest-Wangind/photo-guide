import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * iOS target 只在 macOS 上声明。
 *
 * 原因：Kotlin/Native 的 iOS target 需要 Xcode 工具链，Windows/Linux 上无法构建。
 * 无条件声明会让 Windows 上的 `assembleDebug` 也去配置 iosMain 的元数据编译而失败。
 * 用一个显式判断把它挡掉，保证"同一份仓库：Windows 出 Android，macOS 出双端"。
 * 源码始终留在 src/iosMain 下，切到 Mac 即可编译。
 */
val isMacOs = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    if (isMacOs) {
        /**
         * 只声明 arm64 两个 target，**刻意不含 iosX64**。
         *
         * 原因不是"用不上"，是"用不了"：Compose Multiplatform 1.11.1 已不再发布
         * iosX64（Intel 模拟器）产物。Gradle 报的原文是
         * `No matching variant of org.jetbrains.compose.ui:ui:1.11.1 was found`，
         * 该依赖发布的变体里只有 iosArm64 / iosSimulatorArm64，没有 ios_x64。
         *
         * 声明 iosX64 的后果是每次构建都刷屏一整页 KMP Dependencies Resolution Failure，
         * 却永远编不出东西来——所以直接不声明，把"不支持"这件事写在编译期。
         *
         * 对本机（Intel Mac + Xcode 14.2）意味着：
         *   ✅ iosArm64          → 真机，唯一能真正跑起来的目标（相机本来也必须真机）
         *   ⚠️ iosSimulatorArm64 → 能编，但 Intel Mac 上跑不了（模拟器只能 iosX64，而它没了）
         * 想用模拟器：换 Apple Silicon 机器，用 iosSimulatorArm64。
         */
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "ComposeApp"
                isStatic = true
            }
        }
    }

    /**
     * 显式应用默认层级模板——这一行不是装饰，是修 bug 的。
     *
     * KGP 默认会应用它，但时机在 `kotlin { }` 块**执行完之后**。于是同一个块里
     * `getByName("iosMain")` 必然抛 "KotlinSourceSet with name 'iosMain' not found"：
     * 那一刻容器里只有三个叶子源集（iosX64Main / iosArm64Main / iosSimulatorArm64Main），
     * 负责把它们归拢成共享父源集的层级模板还没跑。
     *
     * 放在三个 iOS target 声明**之后**、`sourceSets { }` 之前调用，保证建树时
     * 三个叶子都已存在，`iosMain` 立刻可用。
     */
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.camerax.core)
            implementation(libs.androidx.camerax.camera2)
            implementation(libs.androidx.camerax.lifecycle)
            implementation(libs.androidx.camerax.view)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
        }

        if (isMacOs) {
            iosMain.dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

android {
    namespace = "com.photoguide"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.photoguide"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}
