plugins {
    id("com.android.library")
    id("dev.ujhhgtg.lsparanoid")
}

lsparanoid {
    classFilter = { true }
}

android {
    namespace = "dev.ujhhgtg.lsparanoid.samples.library_obfuscate"
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
