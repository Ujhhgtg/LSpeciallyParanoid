pluginManagement {
    includeBuild("..")
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "samples"

// Resolves the dev.ujhhgtg.lsparanoid plugin and artifacts from the parent build (no publishing).
includeBuild("..")
include(":application", ":application-global-obfuscate", ":library-obfuscate", ":library", ":library-may-obfuscate")
