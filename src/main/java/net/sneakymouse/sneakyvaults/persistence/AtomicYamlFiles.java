package net.sneakymouse.sneakyvaults.persistence;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Persists small YAML documents by replacing the destination atomically.
 *
 * <p>This class prevents readers from observing a partial file. It does not
 * order concurrent saves to the same path. If callers can save concurrently,
 * they must serialize both snapshot creation and the call to this class for
 * each destination. Otherwise, an older snapshot can replace a newer one.</p>
 */
public final class AtomicYamlFiles {

    private AtomicYamlFiles() {
    }

    public static void save(FileConfiguration configuration, Path target) throws IOException {
        String yaml = configuration.saveToString();
        write(target, yaml.getBytes(StandardCharsets.UTF_8));
    }

    public static void saveResource(InputStream resource, Path target) throws IOException {
        write(target, resource.readAllBytes());
    }

    private static void write(Path target, byte[] contents) throws IOException {
        write(
                target,
                contents,
                (temporary, destination) -> Files.move(
                        temporary,
                        destination,
                        ATOMIC_MOVE,
                        REPLACE_EXISTING
                ),
                Files::deleteIfExists
        );
    }

    static void write(
            Path target,
            byte[] contents,
            Replacer replacer,
            Cleanup cleanup
    ) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Path directory = absoluteTarget.getParent();

        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(
                directory,
                "." + absoluteTarget.getFileName() + ".",
                ".tmp"
        );

        boolean moved = false;
        Throwable failure = null;
        try {
            try (FileChannel channel = FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(contents);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }

            try {
                replacer.replace(temporary, absoluteTarget);
                moved = true;
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic move is not supported for " + absoluteTarget, exception);
            }
        } catch(IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (!moved) {
                try {
                    cleanup.delete(temporary);
                } catch(IOException | RuntimeException | Error cleanupFailure) {
                    if(failure != null)
                        failure.addSuppressed(cleanupFailure);
                    else
                        throw cleanupFailure;
                }
            }
        }
    }

    @FunctionalInterface
    interface Replacer {
        void replace(Path temporary, Path target) throws IOException;
    }

    @FunctionalInterface
    interface Cleanup {
        void delete(Path temporary) throws IOException;
    }
}
