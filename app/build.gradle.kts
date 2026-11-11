plugins {
    id("com.android.application")
}

android {
    namespace = "fr.caperuisseau.apkpureupdater"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.caperuisseau.apkpureupdater"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Seule dépendance : WorkManager pour la vérif périodique en arrière-plan.
    implementation("androidx.work:work-runtime:2.12.0")

    testImplementation("junit:junit:4.13.2")
}
