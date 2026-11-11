import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Versión: la base está en gradle.properties (appVersionName). En GitHub Actions se pasan
 * -PversionCode (siempre creciente, a partir del número de ejecución) y -PversionName.
 */
val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
val appVersionName = (findProperty("versionName") as String?) ?: (property("appVersionName") as String)

/**
 * Firma de versiones: en CI desde variables de entorno (secretos del repositorio); en local desde
 * el fichero indicado por -PkeystoreProperties o ~/.bloqueador-keys/keystore.properties.
 * Sin clave, la versión de publicación se compila sin firmar.
 */
val signingProps: Properties? = run {
    System.getenv("RELEASE_KEYSTORE_FILE")?.let { file ->
        return@run Properties().apply {
            setProperty("storeFile", file)
            setProperty("storePassword", System.getenv("RELEASE_KEYSTORE_PASSWORD"))
            setProperty("keyAlias", System.getenv("RELEASE_KEY_ALIAS"))
            setProperty("keyPassword", System.getenv("RELEASE_KEY_PASSWORD"))
        }
    }
    val path = (findProperty("keystoreProperties") as String?)
        ?: "${System.getProperty("user.home")}/.bloqueador-keys/keystore.properties"
    file(path).takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
}

android {
    namespace = "app.bloqueador"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.bloqueador"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // Repositorio del que la app descarga sus propias actualizaciones (GitHub Releases).
        buildConfigField("String", "UPDATE_REPO", "\"flowmaticc/bloqueador-android\"")
        // API de GitHub. Solo se cambia para pruebas locales (-PupdateApiBase=http://127.0.0.1:8000).
        buildConfigField(
            "String", "UPDATE_API_BASE",
            "\"${(findProperty("updateApiBase") as String?) ?: "https://api.github.com"}\"",
        )
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // org.json real para los tests (en Android viene con el sistema; en la JVM de tests no).
    testImplementation("org.json:json:20240303")
}
