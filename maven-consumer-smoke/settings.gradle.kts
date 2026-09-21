pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    url = uri(providers.gradleProperty("validationRepository").get())
                    metadataSources {
                        if (providers.gradleProperty("pomOnly").orNull != "true") gradleMetadata()
                        mavenPom()
                        if (providers.gradleProperty("pomOnly").orNull == "true") ignoreGradleMetadataRedirection()
                    }
                }
            }
            filter { includeGroup("com.utexo.rgb") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "isolated-maven-sdk-consumer"
