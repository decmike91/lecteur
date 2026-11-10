plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
val keystorePath: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "fr.decmike.lecteur"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.decmike.lecteur"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                // Clé privée fournie par les secrets GitHub (recommandé)
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                // Clé publique incluse dans le dépôt : signature stable pour les mises à jour
                storeFile = file("signing/lecteur.jks")
                storePassword = "lecteur-public"
                keyAlias = "lecteur"
                keyPassword = "lecteur-public"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.md",
                "META-INF/versions/**",
                "META-INF/INDEX.LIST",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Archives : zip, 7z, tar, gz, bz2, xz
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    // Archives RAR (v4)
    implementation("com.github.junrar:junrar:7.5.5")

    // Anciens formats Office (.doc, .xls, .ppt)
    implementation("org.apache.poi:poi:5.3.0")
    implementation("org.apache.poi:poi-scratchpad:5.3.0")
}
