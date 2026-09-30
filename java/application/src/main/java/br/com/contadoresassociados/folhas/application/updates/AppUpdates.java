package br.com.contadoresassociados.folhas.application.updates;

import java.util.function.IntConsumer;

/** Atualização do aplicativo (implementação: Conveyor/jpackage + feed assinado). */
public final class AppUpdates {

    private AppUpdates() {
    }

    public enum Channel { STABLE, BETA }

    public enum State { UNAVAILABLE, NOT_INSTALLED, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY_TO_RESTART, FAILED }

    public record Snapshot(State state, String currentVersion, String latestVersion, Channel channel, int progress,
            String message) {
        public boolean canDownload() {
            return state == State.AVAILABLE;
        }

        public boolean canRestart() {
            return state == State.READY_TO_RESTART;
        }
    }

    public interface Service {
        Snapshot check(Channel channel);

        Snapshot download(IntConsumer progress);

        void applyAndRestart();
    }

    public static final class AppUpdateException extends RuntimeException {
        public AppUpdateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
