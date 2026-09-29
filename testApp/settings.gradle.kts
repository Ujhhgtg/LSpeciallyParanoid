pluginManagement {
    includeBuild("..")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "testApp"

// Resolves the dev.ujhhgtg.lsparanoid plugin and artifacts from the parent build (no publishing).
includeBuild("..")
