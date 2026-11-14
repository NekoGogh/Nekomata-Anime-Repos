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
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "Nekomata-Anime-Repo"

/**
 * Load every extension module found under src/<lang>/<module>.
 */
fun loadAllExtensions() {
    val src = File(rootDir, "src")
    src.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedBy { it.name }
        ?.forEach { langDir ->
            langDir.listFiles()
                ?.filter { it.isDirectory && File(it, "build.gradle").exists() }
                ?.sortedBy { it.name }
                ?.forEach { moduleDir ->
                    include(":src:${langDir.name}:${moduleDir.name}")
                }
        }
}

loadAllExtensions()
