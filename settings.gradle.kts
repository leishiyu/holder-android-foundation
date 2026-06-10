pluginManagement {
    includeBuild("build-logic")
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
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "holder-android-foundation"

include(":core")

include(":hardware")
include(":hardware:base")
include(":hardware:cabinet")
include(":hardware:camera")
include(":hardware:camera:api")
include(":hardware:camera:core")
include(":hardware:camera:view")
include(":hardware:camera:compose")
include(":hardware:camera:driver-camerax")
include(":hardware:camera:driver-camera2")
include(":hardware:camera:driver-camera1")
include(":hardware:camera:driver-uvc")
include(":hardware:camera:face-mlkit")
include(":hardware:scale")
include(":hardware:vendor")
include(":hardware:temperature")

include(":samples")
include(":samples:sample-cabinet")
include(":samples:sample-camera-sdk")
