plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = Config.compileSdk
    buildToolsVersion = Config.buildTools

    defaultConfig {
        minSdk = Config.minSdk
        targetSdk = Config.targetSdk
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(Deps.Kotlin.stdlib)
    compileOnly(Deps.okhttp)
}
