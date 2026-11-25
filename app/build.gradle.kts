import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Versión (gradle.properties): appVersionName ("0.4.0") y appVersionCode, un entero que se sube en 1
 * con cada versión estable. El versionCode real es appVersionCode * 1000 + 999 para la estable (así
 * F-Droid, que compila desde el código, obtiene exactamente el mismo número) y
 * appVersionCode * 1000 + N para las betas (-PbetaBuild=N, 0..998): siempre por encima de la estable
 * anterior y por debajo de la siguiente. -PversionCode / -PversionName fuerzan otro valor (pruebas).
 */
val betaBuild = (findProperty("betaBuild") as String?)?.toInt()?.also { require(it in 0..998) { "betaBuild 0..998" } }
val appVersionCode = (findProperty("versionCode") as String?)?.toInt()
    ?: ((property("appVersionCode") as String).toInt() * 1000 + (betaBuild ?: 999))
val appVersionName = (findProperty("versionName") as String?) ?: (property("appVersionName") as String)

/**
 * Idiomas de la app: inglés (res/values, el idioma por defecto) más uno por cada carpeta
 * res/values-xx con un strings.xml. Para añadir un idioma basta con crear esa carpeta y su fichero:
 * la lista de idiomas de Android (generateLocaleConfig) y el selector de Ajustes se actualizan solos.
 */
val appLanguages: List<String> = listOf("en") + file("src/main/res").listFiles().orEmpty()
    .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").isFile }
    .map { it.name.removePrefix("values-").replace("-r", "-") }
    .filter { Regex("^[a-z]{2,3}(-[A-Z]{2})?$").matches(it) }
    .sorted()

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
        // Servicio de reportes de dominios para analizar (carpeta reportes/ del repositorio).
        buildConfigField("String", "REPORT_URL", "\"https://garbillo-reportes.flowmaticc.workers.dev/reportar\"")
        // Informes de errores anónimos (opcionales): mismo servicio, otra ruta.
        buildConfigField("String", "ERROR_URL", "\"https://garbillo-reportes.flowmaticc.workers.dev/error\"")
        // API de GitHub. Solo se cambia para pruebas locales (-PupdateApiBase=http://127.0.0.1:8000).
        buildConfigField(
            "String", "UPDATE_API_BASE",
            "\"${(findProperty("updateApiBase") as String?) ?: "https://api.github.com"}\"",
        )
        // Idiomas disponibles (ver appLanguages): el selector de Ajustes → Idioma los lee de aquí.
        buildConfigField("String[]", "LANGUAGES", appLanguages.joinToString(", ", "{", "}") { "\"$it\"" })
        // Donde están los update.json estáticos y los APK (-PupdateDownloadBase=http://127.0.0.1:8000 en pruebas).
        buildConfigField(
            "String", "UPDATE_DOWNLOAD_BASE",
            "\"${(findProperty("updateDownloadBase") as String?) ?: "https://github.com"}\"",
        )
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                // v2 (Android 7+) y v3 (Android 9+): v3 permitiría rotar la clave en el futuro sin
                // obligar a desinstalar. v1 no hace falta con minSdk 26.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    /**
     * Dos variantes con el mismo paquete y la misma firma:
     * - github: la de siempre, con actualizador propio desde GitHub Releases.
     * - fdroid: sin actualizador ni permiso de instalar (F-Droid e IzzyOnDroid no permiten que la app
     *   descargue actualizaciones por su cuenta; las actualizaciones las da la tienda).
     */
    flavorDimensions += "distribucion"
    productFlavors {
        create("github") {
            dimension = "distribucion"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("fdroid") {
            dimension = "distribucion"
            buildConfigField("boolean", "SELF_UPDATE", "false")
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
    // Genera la lista de idiomas para Android 13+ (Ajustes del sistema → Apps → Garbillo → Idioma).
    androidResources {
        generateLocaleConfig = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Sin el bloque DEPENDENCY_INFO en la firma: es la lista de librerías cifrada para Google, que nadie
    // más puede leer. F-Droid e IzzyOnDroid piden quitarlo.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        // android.util.Log y similares devuelven valores por defecto en los tests de la JVM.
        unitTests.isReturnDefaultValues = true
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // org.json real para los tests (en Android viene con el sistema; en la JVM de tests no).
    testImplementation("org.json:json:20240303")
}

// Informe de cobertura frente a https://adblock.turtlecute.org (descarga las listas; desactivado
// por defecto). Uso: gradlew testDebugUnitTest --tests '*TurtlecuteCoverageTest' -Pturtlecute
tasks.withType<Test>().configureEach {
    systemProperty("turtlecute", (project.findProperty("turtlecute") != null).toString())
    systemProperty("turtlecute.report", layout.buildDirectory.file("reports/turtlecute.md").get().asFile.path)
    systemProperty("siteHosts", (project.findProperty("siteHosts") as String?) ?: "")
    if (project.findProperty("turtlecute") != null || project.findProperty("siteHosts") != null) {
        outputs.upToDateWhen { false }
    }
}
