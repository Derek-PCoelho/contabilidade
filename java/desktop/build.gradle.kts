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
