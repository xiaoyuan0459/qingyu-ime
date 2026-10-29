import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    compileSdk = 37
    namespace = "com.qingyu.inputmethod"

    // 必须与 imesdk 保持一致。不声明时 AGP 会用它内置的默认 NDK 版本来找 strip 工具，
    // 而本机只装了 30.0.14904198 → 打包阶段报
    //   "Unable to strip library ... due to missing strip tool for ABI 'arm64-v8a'"
    // 并把**未剥离**的 .so 原样打进 APK（libqingyu_hw_jni.so 15 MB、libqingyu_rime.so 52 MB）。
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.qingyu.inputmethod"
        minSdk = 23
        targetSdk = 37
        versionCode = rootProject.extra["qingyuVersionCode"] as Int
        versionName = rootProject.extra["qingyuVersionName"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // 发布/真机只出 arm64-v8a。x86_64 由 debug 变体单独追加（见 buildTypes），
            // 用于在 x86_64 模拟器上做开发期验证。
            abiFilters += listOf("arm64-v8a")
        }

    }

    lint {
        abortOnError = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
            val keystoreProperties = Properties().apply {
                if (keystorePropertiesFile.exists()) {
                    keystorePropertiesFile.inputStream().use { load(it) }
                }
            }
            val keystoreFilePath = System.getenv("RELEASE_STORE_FILE") ?: keystoreProperties.getProperty("storeFile")
            val keystorePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: keystoreProperties.getProperty("storePassword")
            val keyAliasName = System.getenv("RELEASE_KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")
            val keyPasswordValue = System.getenv("RELEASE_KEY_PASSWORD") ?: keystoreProperties.getProperty("keyPassword")
            // .jks 只是常见后缀，内容不一定是 JKS：这里按后缀选 storeType，也允许用
            // RELEASE_STORE_TYPE / keystore.properties 的 storeType 显式覆盖。
            val keystoreType = System.getenv("RELEASE_STORE_TYPE")
                ?: keystoreProperties.getProperty("storeType")
                ?: if (keystoreFilePath?.endsWith(".p12", ignoreCase = true) == true ||
                    keystoreFilePath?.endsWith(".pfx", ignoreCase = true) == true
                ) "PKCS12" else "JKS"
            if (keystoreFilePath != null) {
                val keystoreFile = rootProject.file(keystoreFilePath)
                if (keystoreFile.exists() && keystorePassword != null && keyAliasName != null && keyPasswordValue != null) {
                    storeFile = keystoreFile
                    storeType = keystoreType
                    storePassword = keystorePassword
                    keyAlias = keyAliasName
                    keyPassword = keyPasswordValue
                    enableV1Signing = true
                    enableV2Signing = true
                }
            }
        }
    }

    flavorDimensions += "default"
    productFlavors {
        create("offline") {
            dimension = "default"
            applicationIdSuffix = ".offline"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            applicationIdSuffix = ".release"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard.cfg")
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            // 开发期要在 x86_64 模拟器上跑，故 debug 包额外带上 x86_64 的原生库
            // （imesdk 已把该 ABI 编出来，见其 abiFilters）。
            ndk {
                abiFilters += listOf("x86_64")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(project(":imesdk"))
}

/**
 * APK 打包不用增量状态。
 *
 * AGP 的增量打包会把替换过的大条目**追加**写入 ZIP、旧条目变成没人引用的死字节：
 * 2026-10-27 真机测试量到 `app-offline-debug.apk` 91,781,621 B，而 1450 个条目压缩体积
 * 合计只有 76,288,006 B —— 15.2 MB 空洞，三个最大空洞都紧跟被替换的 `rime-base.zip`
 * 相关条目。删掉 `intermediates/incremental/package<Variant>` 重打就回到条目总和。
 *
 * 每次打包前清掉该变体的增量目录和上一次的 APK 产物：只删增量目录不够 —— 打包器还会把
 * 已存在的输出 APK 当增量基准，替换大条目（词典 pack）时把新条目追加在旧字节之后。
 * 代价是每次多一次全量封装（debug 包几秒钟）。
 */
val packagingIncrementalRoot = layout.buildDirectory.dir("intermediates/incremental")
val apkOutputRoot = layout.buildDirectory.dir("outputs/apk")
tasks.matching {
    // 只针对 APK 封装任务（packageOfflineDebug / packageOfflineRelease），
    // 不碰 package<Variant>Resources、bundle 之类的其它增量目录。
    it.name.startsWith("package") && !it.name.contains("Resources") && !it.name.contains("Bundle")
}.configureEach {
    val incrementalDir = packagingIncrementalRoot.get().asFile.resolve(name)
    // 删完产物必须让 Gradle 真的重跑：否则「输出已被本任务删掉、任务却判定 up-to-date」
    // 会留下一个没有 APK 的 outputs 目录。
    outputs.upToDateWhen { false }
    doFirst {
        incrementalDir.deleteRecursively()
        apkOutputRoot.get().asFile.deleteRecursively()
    }
}
