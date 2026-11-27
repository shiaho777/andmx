pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "AndMX"
include(":app")

providers.gradleProperty("androidLinuxRuntimeDir").orNull?.let { runtimeDir ->
    includeBuild(file(runtimeDir)) {
        dependencySubstitution {
            substitute(module("org.andmx:linux-runtime")).using(project(":runtime"))
        }
    }
}
