import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension


plugins {
    alias(libs.plugins.kotlin) apply false
    id("com.android.library") version "9.4.1" apply false
}


allprojects {
    group = "dev.ujhhgtg.lsparanoid"
    version = "0.13.3"

    plugins.withType(JavaPlugin::class.java) {
        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure(KotlinJvmProjectExtension::class.java) {
            jvmToolchain(17)
            // These artifacts end up on consumers' buildscript classpath, where Gradle strictly
            // pins kotlin-stdlib to its embedded Kotlin version. Target the oldest Kotlin embedded
            // in Gradle 9.x so the published modules don't demand a newer stdlib.
            coreLibrariesVersion = "2.2.0"
            compilerOptions {
                apiVersion.set(KotlinVersion.KOTLIN_2_2)
            }
        }
    }
}
