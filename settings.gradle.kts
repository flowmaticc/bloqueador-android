pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Bloqueador"
include(":app")
// Herramienta privada de pruebas largas: no forma parte del repositorio ni se publica.
// Solo se incluye si existe en la copia local.
if (file("privado/loadtest").isDirectory) {
    include(":loadtest")
    project(":loadtest").projectDir = file("privado/loadtest")
}
