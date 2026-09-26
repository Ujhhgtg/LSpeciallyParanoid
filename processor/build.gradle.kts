plugins {
    alias(libs.plugins.kotlin)
    id("com.vanniktech.maven.publish")
}

dependencies {
    compileOnly(gradleApi())
    implementation(projects.core)
    implementation(libs.grip)
    implementation(libs.asm.common)
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
