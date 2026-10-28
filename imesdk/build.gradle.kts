plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun getAppGitHead(): String =
    ProcessBuilder("git", "rev-parse", "HEAD").start().inputStream.bufferedReader().readText().trim()

fun getAppBuildTime(): String =
    ProcessBuilder("git", "log", "-1", "--pretty=%ai").start().inputStream.bufferedReader().readText().trim()

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    compileSdk = 37
    namespace = "com.qingyu.imemodule"
    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // native 构建由本模块驱动（见下方 externalNativeBuild），故这里必须列出
            // 所有需要**编译**的 ABI：
            //   arm64-v8a —— 真机 / 发布包
            //   x86_64    —— x86_64 模拟器上做开发期验证
            // 旧 SDK 曾声明 4 个 ABI，那是为了随包分发预编译的 libyuyanime.so；
            // 该库在新工程已移除，多编的 ABI 只会白白增加构建时间。
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        buildConfigField("String", "versionName", "\"${rootProject.extra["qingyuVersionName"]}\"")
        buildConfigField("String", "AppCommitHead", "\"${getAppGitHead()}\"")
        buildConfigField("String", "AppBuildTime", "\"${getAppBuildTime()}\"")
    }

    flavorDimensions += "default"
    productFlavors {
        create("offline") {
            dimension = "default"
            buildConfigField("Boolean", "offline", "true")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.cfg")
            resValue("string", "app_name", "@string/ime_qingyu_name")
            resValue("drawable", "app_icon", "@drawable/ic_sdk_launcher")
        }
        getByName("debug") {
            resValue("string", "app_name", "@string/ime_qingyu_name_debug")
            resValue("drawable", "app_icon", "@drawable/ic_sdk_launcher_debug")
        }
    }

    lint {
        abortOnError = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    ndkVersion = "30.0.14904198"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("androidx.emoji2:emoji2:1.5.0")
    implementation("androidx.emoji2:emoji2-views:1.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")
    implementation("com.louiscad.splitties:splitties-resources:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-recyclerview:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-constraintlayout:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-appcompat:3.0.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.room:room-runtime:2.6.1")
    // 手写样本自动上传：网络恢复/进程重启后仍能重试，默认仅 Wi-Fi
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // 本地离线语音识别（Vosk）：模型按需下载，不打包进 APK
    implementation("com.alphacephei:vosk-android:0.3.47")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
