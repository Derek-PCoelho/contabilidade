package br.com.contadoresassociados.folhas.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class SecretKeys {

    static final String SERVICE_NAME = "com.folhasdamichelly.desktop";

    private SecretKeys() {
    }

    static String validate(String key) {
        if (key == null || key.isBlank() || key.length() > 200) {
            throw new IllegalArgumentException("Chave de segredo inválida.");
        }
        return key;
    }

    /** Mesmo nome de arquivo da versão .NET: SHA-256 da chave, hex minúsculo, extensão {@code .dpapi}. */
    static String fileName(String key) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(validate(key).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash) + ".dpapi";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
