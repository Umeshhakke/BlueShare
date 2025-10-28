plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.screencopy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.screencopy"
        minSdk = 25
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat.v171)
    implementation(libs.material.v1110)
    implementation(libs.constraintlayout)
    implementation("androidx.cardview:cardview:1.0.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit.v130)
    androidTestImplementation(libs.espresso.core.v370)

}
