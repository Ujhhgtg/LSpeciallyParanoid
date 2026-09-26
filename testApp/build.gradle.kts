plugins {
    id("com.android.application") version "9.4.1"
    id("dev.ujhhgtg.lsparanoid") version "0.12.0"
}

lsparanoid {
    backend = providers.gradleProperty("lspBackend").orElse("native").get()
    nativeNdkVersion = providers.gradleProperty("lspNdkVersion").orElse("29.0.14206865").get()
    omvllPlugin = providers.gradleProperty("lspOmvllPlugin").orNull
    omvllPythonPath = providers.gradleProperty("lspOmvllPythonPath").orNull
    if (backend == "native") {
        resourceIncludes = setOf("string/*", "plurals/*", "array/*")
        wrappedResourceAccess = true
    }
    // Enable protection for both debug and release to exercise JNI and R8.
    variantFilter = { _ -> true }
}

android {
    namespace = "dev.ujhhgtg.lsparanoid.testapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ujhhgtg.lsparanoid.testapp"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testBuildType = providers.gradleProperty("lspTestBuildType").orElse("debug").get()

    buildTypes {
        debug {
            // No minification for debug - tests run faster
            isMinifyEnabled = false
        }
        release {
            // Enable minification for release to test ProGuard rules
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            testProguardFiles("test-proguard-rules.pro")
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


}

dependencies {
    implementation("dev.ujhhgtg.lsparanoid:core:0.12.0")
    implementation("dev.ujhhgtg.lsparanoid:runtime:0.12.0")

    // Kotlin stdlib needed for annotations used by lsparanoid
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")

    // Testing dependencies
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
