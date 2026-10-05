plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chave fixa de assinatura: vem do GitHub (Secrets) durante o build na nuvem.
// Sem ela (build local), o app é assinado com a chave de debug do próprio computador.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val hasFixedKey = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()

android {
    namespace = "com.meuplayer.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.meuplayer.tv"
        minSdk = 21
        targetSdk = 34
        // O build na nuvem define a versão pela tag (ex.: v1.2.0) e o número do build.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0"
    }

    signingConfigs {
        if (hasFixedKey) {
            create("fixed") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "m3uflow"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("fixed") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfigs.findByName("fixed")?.let { signingConfig = it }
        }
    }

    // A verificação de lint da release atrasa o build e não traz ganho aqui.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
