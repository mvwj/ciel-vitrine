pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ciel-vitrine"

include(":core-model")
include(":pos-format")
include(":pos-reference-decoder")
include(":core-crypto")
include(":control-storage")
include(":catalog-storage")
include(":saga-engine")
include(":reader-share")
