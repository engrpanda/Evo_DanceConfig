plugins {
    alias(libs.plugins.android.application)
    // if you're using Kotlin, also include kotlin-android plugin
    // alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.evo.danceconfig"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.evo.danceconfig"
        minSdk = 21
        targetSdk = 34
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

    // If you use kotlin, enable kotlinOptions here
    // kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // AndroidX basics
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")


    // Optional - GridLayout / CardView (if you need them explicitly)
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.gridlayout:gridlayout:1.0.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
