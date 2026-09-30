package br.com.contadoresassociados.folhas.infrastructure.updates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Instaladores por plataforma. A assinatura do sistema operacional é a segunda barreira da
 * pendência 7.2: mesmo com o manifesto assinado, o pacote só é executado se o SO confirmar o
 * publicador esperado.
 */
public final class PlatformInstallers {

    private PlatformInstallers() {
    }

    /** Executa um comando e devolve a saída; exposto para testes. */
    @FunctionalInterface
    public interface CommandRunner {
        Result run(List<String> command, Duration timeout) throws IOException;

        record Result(int exitCode, String output) {
        }

        CommandRunner SYSTEM = (command, timeout) -> {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Tempo esgotado ao verificar o pacote.");
                }
                return new Result(process.exitValue(),
                        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        };
    }

    public static SignedFeedAppUpdateService.Installer forCurrentPlatform(String expectedPublisher,
            Runnable exitApplication) {
        var platform = SignedFeedAppUpdateService.currentPlatform();
        if (platform == null) {
            return null;
        }
        return platform.startsWith("win") ? new Windows(expectedPublisher, CommandRunner.SYSTEM, exitApplication)
                : new MacOs(expectedPublisher, CommandRunner.SYSTEM, exitApplication);
    }

    /** Windows: Authenticode via {@code Get-AuthenticodeSignature}; instala com {@code msiexec}. */
    public static final class Windows implements SignedFeedAppUpdateService.Installer {
        private final String expectedSubject;
        private final CommandRunner runner;
        private final Runnable exit;

        public Windows(String expectedSubject, CommandRunner runner, Runnable exit) {
            this.expectedSubject = require(expectedSubject);
            this.runner = runner;
            this.exit = exit;
        }

        @Override
        public void verifyPlatformSignature(Path packageFile) {
            var script = "$s = Get-AuthenticodeSignature -LiteralPath $args[0]; "
                    + "Write-Output ($s.Status.ToString() + '|' + $s.SignerCertificate.Subject)";
            var result = run(runner, List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script,
                    packageFile.toString()));
            var line = result.output().strip();
            var parts = line.split("\\|", 2);
            if (result.exitCode() != 0 || parts.length != 2 || !"Valid".equals(parts[0])
                    || !subjectMatches(parts[1], expectedSubject)) {
                throw new IllegalStateException("Assinatura Authenticode inválida ou de outro publicador.");
            }
        }

        @Override
        public void installAndRestart(Path packageFile) {
            try {
                var name = packageFile.getFileName().toString().toLowerCase(Locale.ROOT);
                var command = name.endsWith(".msi")
                        ? List.of("msiexec.exe", "/i", packageFile.toString(), "/passive", "/norestart")
                        : List.of(packageFile.toString(), "/SILENT");
                new ProcessBuilder(command).start();
            } catch (IOException e) {
                throw new IllegalStateException("Não foi possível iniciar o instalador.", e);
            }
            exit.run();
        }
    }

    /** macOS: {@code pkgutil --check-signature} + {@code spctl} (notarização); instala com {@code open}. */
    public static final class MacOs implements SignedFeedAppUpdateService.Installer {
        private final String expectedTeam;
        private final CommandRunner runner;
        private final Runnable exit;

        public MacOs(String expectedTeam, CommandRunner runner, Runnable exit) {
            this.expectedTeam = require(expectedTeam);
            this.runner = runner;
            this.exit = exit;
        }

        @Override
        public void verifyPlatformSignature(Path packageFile) {
            var signature = run(runner, List.of("/usr/sbin/pkgutil", "--check-signature", packageFile.toString()));
            if (signature.exitCode() != 0 || !signature.output().contains("Status: signed by a developer certificate")
                    || !signature.output().contains("(" + expectedTeam + ")")) {
                throw new IllegalStateException("O pacote não foi assinado pelo time esperado.");
            }
            var gatekeeper = run(runner, List.of("/usr/sbin/spctl", "--assess", "--type", "install", "-vv",
                    packageFile.toString()));
            if (gatekeeper.exitCode() != 0 || !gatekeeper.output().contains("accepted")) {
                throw new IllegalStateException("O pacote não foi aceito pelo Gatekeeper (notarização).");
            }
        }

        @Override
        public void installAndRestart(Path packageFile) {
            try {
                new ProcessBuilder("/usr/bin/open", packageFile.toString()).start();
            } catch (IOException e) {
                throw new IllegalStateException("Não foi possível abrir o instalador.", e);
            }
            exit.run();
        }
    }

    static boolean subjectMatches(String subject, String expected) {
        // compara o CN exato, não "contém" (evita CN=Folhas da Michelly Falso)
        for (var part : subject.split(",")) {
            var p = part.strip();
            if (p.regionMatches(true, 0, "CN=", 0, 3)) {
                var cn = p.substring(3).replace("\"", "").strip();
                return cn.equals(expected);
            }
        }
        return false;
    }

    private static CommandRunner.Result run(CommandRunner runner, List<String> command) {
        try {
            return runner.run(command, Duration.ofMinutes(2));
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível verificar a assinatura do pacote.", e);
        }
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Publicador esperado não configurado.");
        }
        return value.strip();
    }
}
