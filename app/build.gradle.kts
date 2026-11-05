plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Native pipeline is optional for the first vertical slice. Install NDK + CMake,
// then build with: ./gradlew assembleDebug -PnativeBuild=true
val nativeBuild = providers.gradleProperty("nativeBuild").orNull?.toBoolean() ?: false

// Personal-use configuration loaded from .env (never commit .env).
fun envValue(name: String, fallback: String = ""): String {
    val file = rootProject.file(".env")
    if (!file.exists()) return fallback
    return file.readLines()
        .firstOrNull { it.trimStart().startsWith("$name=") }
        ?.substringAfter("=")
        ?.trim()
        ?.removeSurrounding("\"")
        ?.removeSurrounding("'")
        ?: fallback
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val deepSeekApiKey = envValue("DEEPSEEK_API_KEY")
val deepSeekBaseUrl = envValue("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
val deepSeekModel = envValue("DEEPSEEK_MODEL", "deepseek-flash")

android {
    namespace = "com.example.imageedit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.imageedit"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEEPSEEK_API_KEY", deepSeekApiKey.asBuildConfigString())
        buildConfigField("String", "DEEPSEEK_BASE_URL", deepSeekBaseUrl.asBuildConfigString())
        buildConfigField("String", "DEEPSEEK_MODEL", deepSeekModel.asBuildConfigString())

        // 模拟器需要 x86_64；发布前可再裁剪为 arm64-only。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    if (nativeBuild) {
        ndkVersion = "27.0.12077973"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.opencv)
    implementation(libs.onnxruntime.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}