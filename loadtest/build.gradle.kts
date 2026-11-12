// App auxiliar para pruebas largas en un móvil de pruebas: genera tráfico DNS y web periódico a
// través del filtro de Bloqueador y lo registra. No se publica. Ver docs/PRUEBAS.md.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.bloqueador.loadtest"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.bloqueador.loadtest"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
