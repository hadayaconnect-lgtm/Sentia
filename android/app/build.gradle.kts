plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dj.sentia"
    compileSdk = 35

    defaultConfig {
        applicationId = "dj.sentia"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        val server = (project.findProperty("sentia.serverUrl") as String?) ?: "https://VOTRE-SERVEUR.vercel.app"
        buildConfigField("String", "SERVER_URL", "\"${server.trimEnd('/')}\"")
        resourceConfigurations += listOf("fr", "en", "so", "ar")
    }

    buildFeatures { buildConfig = true; viewBinding = false }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Le modèle de sons (YAMNet, ~4 Mo) est un fichier .tflite : ne pas le compresser.
    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // Sons (expérimental) : classification audio locale.
    implementation("org.tensorflow:tensorflow-lite-task-audio:0.4.4")

    testImplementation("junit:junit:4.13.2")
}
