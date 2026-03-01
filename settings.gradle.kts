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

rootProject.name = "CozmoPlay"

include(":app")
include(":cozmo-types")
include(":cozmo-wifi")
include(":cozmo-protocol")
include(":cozmo-camera")
include(":test-suite")
