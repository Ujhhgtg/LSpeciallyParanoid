plugins {
    id("com.android.library")
}

android {
    namespace = "dev.ujhhgtg.lsparanoid.samples.library_may_obfuscate"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.ujhhgtg.lsparanoid:core:0.11.0")
}
