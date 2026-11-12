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
// App auxiliar de pruebas largas (no se publica). Ver docs/PRUEBAS.md.
include(":loadtest")
