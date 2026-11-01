plugins {
    id("com.holderzone.android.camera.family.library")
}

android {
    namespace = "com.holderzone.hardware.camera"
}

dependencies {
    implementation(project(":hardware:camera:api"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
}
