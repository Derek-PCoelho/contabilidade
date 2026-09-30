package br.com.contadoresassociados.folhas.application.dispatch;

/** Sessão OAuth de uma conta de e-mail (Gmail, Microsoft 365). */
public interface EmailAccountSession {

    record Status(String providerKey, boolean configured, boolean connected, String accountId, String displayName,
            String errorCode) {
    }

    String providerKey();

    Status status();

    Status connect();

    void disconnect();

    String accessToken(boolean requireSendPermission);

    final class EmailAccountSessionException extends RuntimeException {
        private final String code;

        public EmailAccountSessionException(String code, String message) {
            super(message);
            this.code = code;
        }

        public EmailAccountSessionException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
