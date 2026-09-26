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
    id("com.vanniktech.maven.publish")
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

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }

    coordinates("dev.ujhhgtg.lsparanoid", project.name, version.toString())

    pom {
        name = "LSParanoid - ${project.name}"
        description = "String obfuscator for Android applications"
        url = "https://github.com/Ujhhgtg/LSpeciallyParanoid"

        licenses {
            license {
                name = "Apache License 2.0"
                url = "https://github.com/Ujhhgtg/LSpeciallyParanoid/blob/master/LICENSE.txt"
            }
        }

        developers {
            developer {
                name = "Ujhhgtg"
                url = "https://github.com/Ujhhgtg"
            }
        }

        scm {
            connection = "scm:git:https://github.com/Ujhhgtg/LSpeciallyParanoid.git"
            url = "https://github.com/Ujhhgtg/LSpeciallyParanoid"
        }
    }
}
