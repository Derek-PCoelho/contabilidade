package br.com.contadoresassociados.folhas.application.security;

/** Envelope criptografado de backup (PBKDF2-SHA256 600k + AES-256-GCM). */
public interface ProtectedBackupService {

    byte[] protect(byte[] content, char[] password);

    byte[] unprotect(byte[] envelope, char[] password);

    final class ProtectedBackupException extends RuntimeException {
        public ProtectedBackupException(String message) {
            super(message);
        }

        public ProtectedBackupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
