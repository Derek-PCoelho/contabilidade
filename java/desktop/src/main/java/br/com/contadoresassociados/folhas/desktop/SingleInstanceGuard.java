package br.com.contadoresassociados.folhas.desktop;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Uma instância por conta de usuário (mesmo arquivo {@code desktop-instance.lock} da versão .NET). */
public final class SingleInstanceGuard implements AutoCloseable {

    static final String LOCK_FILE_NAME = "desktop-instance.lock";
    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstanceGuard(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static SingleInstanceGuard tryAcquire(Path dataDirectory) {
        try {
            Files.createDirectories(dataDirectory);
            var channel = FileChannel.open(dataDirectory.resolve(LOCK_FILE_NAME),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                var lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    return null;
                }
                return new SingleInstanceGuard(channel, lock);
            } catch (OverlappingFileLockException e) {
                channel.close();
                return null;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível preparar o bloqueio de instância única.", e);
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException ignored) {
            // O sistema libera o bloqueio ao encerrar o processo.
        }
    }
}
