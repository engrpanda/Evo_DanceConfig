plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.evo.danceconfig"
    compileSdk = 34  // ✅ Latest stable

    defaultConfig {
        applicationId = "com.evo.danceconfig"
        minSdk = 21
        targetSdk = 34  // ✅ Targets Android 12+ requirements
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation("androidx.core:core-ktx:1.13.1") // ✅ Missing before

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
