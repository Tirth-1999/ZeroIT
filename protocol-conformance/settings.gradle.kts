pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    resolutionStrategy { eachPlugin {
        if (requested.id.id == "org.jetbrains.kotlin.jvm") useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
    } }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "zeroit-protocol-conformance"
