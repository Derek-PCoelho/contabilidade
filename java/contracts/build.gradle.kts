dependencies {
    "api"(platform(libs.jackson.bom))
    "api"(libs.jackson.databind)
    "api"(libs.jackson.jsr310)
    "testImplementation"("org.junit.jupiter:junit-jupiter-params")
}
