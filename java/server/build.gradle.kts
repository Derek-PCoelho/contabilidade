plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    "implementation"(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    "implementation"(project(":application"))
    "implementation"("org.springframework.boot:spring-boot-starter-web")
    "implementation"("org.springframework.boot:spring-boot-starter-jdbc")
    "implementation"("org.springframework.boot:spring-boot-starter-websocket")
    "implementation"("org.flywaydb:flyway-core")
    "implementation"("org.flywaydb:flyway-database-postgresql")
    "implementation"(libs.nimbus.jose)
    "implementation"(libs.zxing)
    "implementation"("org.postgresql:postgresql")
    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testImplementation"(project(":infrastructure"))
    "testImplementation"("org.junit.jupiter:junit-jupiter-params")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("folhas-server.jar")
}

tasks.withType<Test>().configureEach {
    systemProperty("folhas.test.pg.url", System.getenv("FOLHAS_TEST_PG_URL") ?: "jdbc:postgresql://127.0.0.1:55432/postgres")
    systemProperty("folhas.test.pg.user", System.getenv("FOLHAS_TEST_PG_USER") ?: "postgres")
    systemProperty("folhas.test.pg.password", System.getenv("FOLHAS_TEST_PG_PASSWORD") ?: "")
}
