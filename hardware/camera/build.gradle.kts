import org.gradle.api.plugins.BasePluginExtension

plugins {
    id("com.holderzone.android.camera.family.library")
    id("com.holderzone.android.local.maven.publish")
}

android {
    namespace = "com.holderzone.hardware.camera"

    defaultConfig {
        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }
    }

    sourceSets {
        named("main") {
            manifest.srcFile("src/sdk/AndroidManifest.xml")
            java.setSrcDirs(
                listOf(
                    "src/sdk/kotlin",
                    "api/src/main/java",
                    "core/src/main/java",
                    "view/src/main/java",
                    "driver-camerax/src/main/java",
                    "driver-camera2/src/main/java",
                    "driver-camera1/src/main/java",
                    "driver-uvc/src/main/java",
                )
            )
            res.setSrcDirs(listOf("driver-uvc/src/main/res"))
            jniLibs.srcDirs("driver-uvc/libs")
        }
    }

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

extensions.configure<BasePluginExtension> {
    archivesName.set("hardware-camera-sdk-${project.version}")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.androidx.camera.view)
}
