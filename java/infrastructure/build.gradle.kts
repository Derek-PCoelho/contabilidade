dependencies {
    "api"(project(":application"))
    "implementation"(libs.sqlite)
    "implementation"(libs.slf4j)
    "implementation"(libs.jna)
    "implementation"(libs.pdfbox)
    "implementation"(libs.poi)
    "testImplementation"("org.junit.jupiter:junit-jupiter-params")
}
