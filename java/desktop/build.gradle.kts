plugins {
    application
    alias(libs.plugins.javafx)
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.swing")
}

dependencies {
    "implementation"(project(":infrastructure"))
    "implementation"(libs.slf4j)
    "runtimeOnly"(libs.logback)
    "testImplementation"("org.junit.jupiter:junit-jupiter-params")
    "testImplementation"(libs.pdfbox)
}

application {
    mainClass.set("br.com.contadoresassociados.folhas.desktop.DesktopLauncher")
    applicationName = "folhas-da-michelly"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dprism.lcdtext=false", "-Dfile.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    // Pré-visualização: FOLHAS_DESKTOP_* e DISPLAY são repassados pelo ambiente.
    environment("LANG", "C.UTF-8")
}

tasks.withType<Test>().configureEach {
    // O teste de fumaça da janela usa o display do ambiente (Xvfb no CI); sem display, é ignorado.
    System.getenv("DISPLAY")?.let { environment("DISPLAY", it) }
    jvmArgs("-Dprism.order=sw", "-Djava.awt.headless=false")
}
