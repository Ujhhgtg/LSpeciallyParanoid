plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "dev.ujhhgtg.lsparanoid.runtime"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

mavenPublishing {
    configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
        javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
        sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
        variant = "release",
    ))
    publishToMavenCentral(automaticRelease = true)
    if (project.hasProperty("signingInMemoryKey")) signAllPublications()
    coordinates("dev.ujhhgtg.lsparanoid", "runtime", version.toString())
    pom {
        name = "LSpeciallyParanoid Android resource runtime"
        description = "Explicit resource decoding adapters for protected Android strings"
        url = "https://github.com/Ujhhgtg/LSpeciallyParanoid"
        licenses {
            license {
                name = "Apache License 2.0"
                url = "https://github.com/Ujhhgtg/LSpeciallyParanoid/blob/master/LICENSE.txt"
            }
        }
        developers { developer { name = "Ujhhgtg"; url = "https://github.com/Ujhhgtg" } }
        scm {
            connection = "scm:git:https://github.com/Ujhhgtg/LSpeciallyParanoid.git"
            url = "https://github.com/Ujhhgtg/LSpeciallyParanoid"
        }
    }
}
