plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "io.wiesler.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.wiesler.android"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "io.wiesler.android.PersistenceTest"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencyLocking { lockAllConfigurations() }
dependencies { testImplementation("junit:junit:4.13.2") }
