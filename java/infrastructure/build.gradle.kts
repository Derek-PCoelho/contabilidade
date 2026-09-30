dependencies {
    "api"(project(":application"))
    "implementation"(libs.sqlite)
    "implementation"(libs.slf4j)
    "implementation"(libs.jna)
    "implementation"(libs.pdfbox)
    "implementation"(libs.poi)
    "implementation"(libs.angus.mail)
    "implementation"(libs.msal)
    "testImplementation"("org.junit.jupiter:junit-jupiter-params")
}
