plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.evability.notes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.evability.notes"
        minSdk = 26
        targetSdk = 35
        versionCode = 41
        versionName = "1.11.1"
    }

    buildTypes {
        release {
            // R8 reduce y optimiza el código y quita recursos sin usar: la app arranca antes y va más fluida.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Firmado con la clave de depuración para poder instalar el APK directamente.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false } // la comprobación extra de lint en release solo alarga la compilación
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

// El tablero, las cartas, las fichas y la música del Evapoly viven en docs/ (los usa también la web): se copian a los assets al compilar.
val syncPolyAssets by tasks.registering(Copy::class) {
    from("../docs/data/evapoly.json", "../docs/data/evapoly-tokens.json", "../docs/data/evapoly-icons.json", "../docs/music/evapoly.mp3", "../docs/music/evapoly-dados.mp3", "../docs/music/evapoly-policia.mp3")
    into(layout.buildDirectory.dir("generated/polyAssets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/polyAssets"))
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(syncPolyAssets) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
}
