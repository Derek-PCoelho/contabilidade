package br.com.contadoresassociados.folhas.desktop.ui;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.application.Platform;

/**
 * Executa operações de serviço fora da thread da interface e devolve o resultado nela
 * (pendência 6.x: nenhuma chamada de banco ou rede congela a janela). Em testes, use
 * {@link #synchronous()}.
 */
public interface UiTasks {

    <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError);

    default void run(Runnable work, Runnable onSuccess, Consumer<Throwable> onError) {
        run(() -> {
            work.run();
            return Boolean.TRUE;
        }, ignored -> onSuccess.run(), onError);
    }

    static UiTasks synchronous() {
        return new UiTasks() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
                T value;
                try {
                    value = work.call();
                } catch (Exception | Error e) {
                    onError.accept(e);
                    return;
                }
                onSuccess.accept(value);
            }
        };
    }

    static UiTasks background() {
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("folhas-ui-task-", 0).factory());
        return new UiTasks() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
                executor.execute(() -> {
                    try {
                        var value = work.call();
                        Platform.runLater(() -> onSuccess.accept(value));
                    } catch (Exception | Error e) {
                        Platform.runLater(() -> onError.accept(e));
                    }
                });
            }
        };
    }
}
