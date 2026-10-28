import java.util.Properties

plugins {
    id("com.android.application") version "9.4.0" apply false
    id("com.android.library") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.2.20" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val configuredVersionCode = releaseVersion.getProperty("versionCode").toInt()
val configuredVersionName = releaseVersion.getProperty("versionName")
require(configuredVersionCode in 1..2_100_000_000) { "versionCode is outside Android's supported range" }
require(configuredVersionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"))) {
    "versionName must be an explicit semantic version"
}
extra["qingyuVersionCode"] = configuredVersionCode
extra["qingyuVersionName"] = configuredVersionName

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
