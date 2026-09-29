import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

plugins {
    idea
    alias(libs.plugins.kotlin)
    `java-gradle-plugin`
}

group = "dev.ujhhgtg.lsparanoid"
version = rootProject.version

gradlePlugin {
    plugins {
        create("lsparanoid") {
            id = "dev.ujhhgtg.lsparanoid"
            implementationClass = "dev.ujhhgtg.lsparanoid.plugin.LSParanoidPlugin"
            displayName = "LSParanoid"
            description = "String obfuscator for Android applications"
        }
    }
}

dependencies {
    implementation(projects.core)
    implementation(projects.processor)
    // Provided at runtime by AGP (which also bundles KGP since AGP 9); shipping them as runtime
    // dependencies drags duplicate AGP/Kotlin artifacts onto consumers' buildscript classpath.
    compileOnly(libs.kotlin.api)
    compileOnly(libs.agp.api)
}

abstract class GenerateBuildClass : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val buildClassFile = outputDir.file("main/java/dev/ujhhgtg/lsparanoid/plugin/Build.java").get().asFile
        buildClassFile.parentFile.mkdirs()
        buildClassFile.writeText(
            """
            package dev.ujhhgtg.lsparanoid.plugin;
            /**
             * The type Build.
             */
            public class Build {
               /**
                * The constant VERSION.
                */
               public static final String VERSION = "${version.get()}";
            }""".trimIndent()
        )
    }
}

val generatedDir = File(projectDir, "generated")
val generatedJavaSourcesDir = File(generatedDir, "main/java")

val genTask = tasks.register<GenerateBuildClass>("generateBuildClass") {
    version.set(project.version.toString())
    outputDir.set(generatedDir)
}

sourceSets {
    main {
        java {
            srcDir(generatedJavaSourcesDir)
        }
    }
}

tasks.withType(KotlinCompile::class.java) {
    dependsOn(genTask)
}

tasks.withType(Jar::class.java) {
    dependsOn(genTask)
}

idea {
    module {
        generatedSourceDirs.add(generatedJavaSourcesDir)
    }
}
