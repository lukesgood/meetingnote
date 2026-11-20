pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "MeetingNote"
include(":app", ":native-whisper", ":native-llama", ":native-whisper-base", ":native-llama-base", ":native-llama-gpu")
