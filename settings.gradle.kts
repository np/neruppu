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
        // gomobile AAR: from the Nix store in `nix develop` (NERUPPU_AGE_MAVEN),
        // otherwise as built by age-encryption/build-aar.sh
        maven {
            url = uri(System.getenv("NERUPPU_AGE_MAVEN") ?: "age-encryption/maven")
            content { includeGroup("org.havenapp.neruppu") }
        }
    }
}

rootProject.name = "Neruppu"
include(":app")
include(":domain")
include(":data")
include(":ui")
include(":core")
