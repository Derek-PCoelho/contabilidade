package br.com.contadoresassociados.folhas.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.security.ProtectedBackupService.ProtectedBackupException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityTest {

    static final char[] PASSWORD = "senha-muito-segura-ção".toCharArray();

    @TempDir
    Path dir;

    @Test
    void opensEnvelopeProducedByIndependentImplementation() throws Exception {
        var envelope = getClass().getResourceAsStream("/backup/reference.fdmbackup").readAllBytes();
        var clear = new AesGcmProtectedBackupService().unprotect(envelope, PASSWORD);
        assertThat(new String(clear, StandardCharsets.UTF_8)).isEqualTo("conteúdo de teste .NET");
    }

    @Test
    void roundTripAndTamperDetection() throws Exception {
        var svc = new AesGcmProtectedBackupService();
        var env = svc.protect("dados".getBytes(StandardCharsets.UTF_8), PASSWORD);
        var json = new String(env, StandardCharsets.UTF_8);
        assertThat(json).startsWith("{\"version\":1,\"kdf\":\"PBKDF2-HMAC-SHA256\",\"iterations\":600000");
        Files.write(dir.resolve("java.fdmbackup"), env);
        assertThat(new String(svc.unprotect(env, PASSWORD), StandardCharsets.UTF_8)).isEqualTo("dados");
        assertThatThrownBy(() -> svc.unprotect(env, "outra-senha-longa".toCharArray()))
                .isInstanceOf(ProtectedBackupException.class).hasMessageContaining("Confira a senha");
        var tampered = json.replace("\"iterations\":600000", "\"iterations\":1000").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> svc.unprotect(tampered, PASSWORD)).isInstanceOf(ProtectedBackupException.class);
        assertThatThrownBy(() -> svc.protect(new byte[] {1}, "curta".toCharArray()))
                .hasMessageContaining("12 caracteres");
    }

    @Test
    void redactorCoversGapsFromAnalysis() {
        var r = new DefaultSensitiveTextRedactor();
        var text = "CPF 52998224725, CNPJ 11.222.333/0001-81, alfa 12ABC34501DE35, e-mail ana.souza@empresa.com.br,"
                + " Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmn,"
                + " {\"refresh_token\":\"1//0gAbc-XYZ\"} salário R$ 12.345,67\nlinha2";
        var out = r.redact(text, 2000);
        assertThat(out).doesNotContain("52998224725", "11.222.333", "12ABC34501DE35", "ana.souza", "eyJ", "1//0gAbc",
                "12.345,67", "\n");
        assertThat(out).contains("a***@empresa.com.br", "[DOCUMENTO FISCAL REDIGIDO]", "[VALOR REDIGIDO]");
        assertThat(r.redact("Erro HTTP 503 no Gmail: serviço indisponível", 2000))
                .isEqualTo("Erro HTTP 503 no Gmail: serviço indisponível");
        assertThat(r.redact("x".repeat(50), 10)).hasSize(10);
    }

    @Test
    void secretFileNamesMatchDotNet() {
        // SHA-256("gmail.refresh_token") em hex minúsculo, como o WindowsDpapiSecretStore do .NET.
        assertThat(SecretKeys.fileName("gmail.refresh_token")).matches("[0-9a-f]{64}\\.dpapi");
        var store = new InMemorySecretStore();
        store.store("k", "v");
        assertThat(store.retrieve("k")).contains("v");
        store.remove("k");
        assertThat(store.retrieve("k")).isEmpty();
    }
}
