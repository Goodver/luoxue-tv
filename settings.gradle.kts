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
        // GeckoView (Mozilla Firefox 内核) 官方 Maven
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}
rootProject.name = "luoxue-tv"
include(":app")
