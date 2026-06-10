plugins {
    id("com.holderzone.android.camera.family.library")
}

android {
    namespace = "com.holderzone.hardware.camera"
}

dependencies {
    implementation(project(":hardware:camera:api"))
}
