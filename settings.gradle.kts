pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "PortalX"
include(":app")
// v0.7.1 module split: core layers (net → data → ui) and one module per feature area. Features depend only on :core:ui.
include(":core:net", ":core:data", ":core:ui")
include(":feature:auth", ":feature:today", ":feature:work", ":feature:workspace")
