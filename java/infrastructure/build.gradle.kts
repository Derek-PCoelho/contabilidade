dependencies {
    "api"(project(":application"))
    "implementation"(libs.sqlite)
    "implementation"(libs.slf4j)
    "testImplementation"("org.junit.jupiter:junit-jupiter-params")
}
