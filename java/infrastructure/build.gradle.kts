dependencies {
    "api"(project(":application"))
    "implementation"(libs.sqlite)
    "implementation"(libs.slf4j)
    "implementation"(libs.jna)
    "testImplementation"("org.junit.jupiter:junit-jupiter-params")
}
