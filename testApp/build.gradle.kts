plugins {
    id("com.android.application") version "9.4.1"
    id("dev.ujhhgtg.lsparanoid") version "0.11.0"
}

lsparanoid {
    // Enable obfuscation for both debug and release to allow testing
    variantFilter = { _ -> true }
}

android {
    namespace = "dev.ujhhgtg.lsparanoid.testapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ujhhgtg.lsparanoid.testapp"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // No minification for debug - tests run faster
            isMinifyEnabled = false
        }
        release {
            // Enable minification for release to test ProGuard rules
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel8api35") {
                    device = "Pixel 8"
                    apiLevel = 35
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

dependencies {
    implementation("dev.ujhhgtg.lsparanoid:core:0.11.0")

    // Kotlin stdlib needed for annotations used by lsparanoid
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")

    // Testing dependencies
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
