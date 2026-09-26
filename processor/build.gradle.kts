plugins {
    alias(libs.plugins.kotlin)
    id("com.vanniktech.maven.publish")
}

dependencies {
    compileOnly(gradleApi())
    implementation(projects.core)
    implementation(libs.grip)
    implementation(libs.asm.common)
    implementation("org.ow2.asm:asm-tree:9.10.1")
    implementation("org.ow2.asm:asm-analysis:9.10.1")
    implementation("com.android.tools.build:aapt2-proto:9.4.1-15978811")
    implementation("com.google.protobuf:protobuf-java:3.25.8")
    testImplementation(gradleApi())
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
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
