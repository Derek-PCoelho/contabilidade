rootProject.name = "folhas-da-michelly"

include("domain", "contracts", "application", "infrastructure", "server", "desktop")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
