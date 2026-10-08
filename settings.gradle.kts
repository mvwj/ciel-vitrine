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

include(":app")

include(":core-model")
include(":pos-format")
include(":pos-reference-decoder")
include(":core-crypto")
include(":control-storage")
include(":catalog-storage")
include(":saga-engine")
include(":reader-share")
include(":provider-adapter")
include(":state-machine-tests")
include(":archive-engine")

