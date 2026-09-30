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

// ---------------------------------------------------------------------------------------------
// Empacotamento nativo (jpackage do JDK 25). Deve rodar no sistema de destino:
//   Windows: ./gradlew :desktop:packageNative  → build/package/Folhas da Michelly-<v>.msi
//   macOS:   ./gradlew :desktop:packageNative  → build/package/Folhas da Michelly-<v>.dmg
// A assinatura (Authenticode / Developer ID + notarização) é feita no pipeline de release,
// com os certificados fora do repositório — ver docs/java/PACKAGING.md.
// ---------------------------------------------------------------------------------------------
val appVersion = (project.findProperty("appVersion") as String?) ?: "1.0.0"

tasks.register<Exec>("packageNative") {
    group = "distribution"
    description = "Gera o instalador nativo (MSI no Windows, DMG no macOS) com jpackage."
    dependsOn("installDist")
    val os = System.getProperty("os.name").lowercase()
    val installDir = layout.buildDirectory.dir("install/folhas-da-michelly/lib")
    val output = layout.buildDirectory.dir("package")
    doFirst { output.get().asFile.mkdirs() }
    val jpackage = File(System.getProperty("java.home"), "bin/jpackage").absolutePath
    val common = mutableListOf(
        jpackage,
        "--name", "Folhas da Michelly",
        "--app-version", appVersion,
        "--vendor", "Contadores Associados",
        "--description", "Organização e envio de folhas e documentos contábeis",
        "--input", installDir.get().asFile.absolutePath,
        "--main-jar", "desktop-${project.version}.jar",
        "--main-class", "br.com.contadoresassociados.folhas.desktop.DesktopLauncher",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dprism.lcdtext=false",
        "--dest", output.get().asFile.absolutePath,
    )
    when {
        os.contains("win") -> common += listOf("--type", "msi", "--win-menu", "--win-shortcut", "--win-dir-chooser",
            "--win-per-user-install", "--win-upgrade-uuid", "6f1f3c2e-7a4b-4c3e-9b1d-5d7f0b2c9a11",
            "--icon", file("packaging/windows/app.ico").absolutePath)
        os.contains("mac") -> common += listOf("--type", "dmg", "--mac-package-identifier", "br.com.contadoresassociados.folhas",
            "--mac-package-name", "Folhas da Michelly", "--icon", file("packaging/macos/app.icns").absolutePath)
        else -> common += listOf("--type", "app-image")
    }
    commandLine(common)
}
