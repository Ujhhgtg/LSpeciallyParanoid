plugins {
    id("com.android.application")
    id("dev.ujhhgtg.lsparanoid")
}

lsparanoid {
    classFilter = { true }
    includeDependencies = true
    variantFilter = { variant -> variant.name == "release" }
}

android {
    namespace = "dev.ujhhgtg.lsparanoid.samples.application_global_obfuscate"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.ujhhgtg.lsparanoid.samples.application_global_obfuscate"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    compileOnly("androidx.annotation:annotation:1.11.0")
    implementation(project(":library"))
    implementation(project(":library-obfuscate"))
    implementation(project(":library-may-obfuscate"))
}
