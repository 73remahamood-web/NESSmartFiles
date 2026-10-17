pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "NES-Smart-Files"
include(":app")
// The official llama.cpp Android binding is checked out by scripts/fetch-llama.sh (GitHub Actions).
// It is intentionally not included in the ZIP to keep the repository small.
val aiLibrary = file("vendor/llama.cpp/examples/llama.android/lib")
if (!aiLibrary.isDirectory) {
    error("Missing llama.cpp Android library. Run bash scripts/fetch-llama.sh before Gradle.")
}
include(":aichat")
project(":aichat").projectDir = aiLibrary
