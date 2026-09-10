package net.sneakymouse.sneakyvaults.persistence;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicYamlFilesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void saveReplacesExistingYamlWithCompleteConfiguration() throws Exception {
        Path target = temporaryDirectory.resolve("player.yml");
        Files.writeString(target, "obsolete: true\n");

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("player_vaults.1.items", List.of("first", "second"));
        configuration.set("player_vaults.1.paperConverted", true);

        AtomicYamlFiles.save(configuration, target);

        YamlConfiguration saved = YamlConfiguration.loadConfiguration(target.toFile());
        assertAll(
                () -> assertFalse(saved.contains("obsolete")),
                () -> assertEquals(
                        List.of("first", "second"),
                        saved.getStringList("player_vaults.1.items")
                ),
                () -> assertEquals(true, saved.getBoolean("player_vaults.1.paperConverted"))
        );
    }

    @Test
    void saveResourcePreservesThePackagedYaml() throws Exception {
        Path target = temporaryDirectory.resolve("config.yml");
        byte[] packagedYaml = "# Keep this comment\nsetting: true\n".getBytes(UTF_8);

        AtomicYamlFiles.saveResource(new ByteArrayInputStream(packagedYaml), target);

        assertArrayEquals(packagedYaml, Files.readAllBytes(target));
    }

    @Test
    void cleanupFailureDoesNotHideTheReplacementFailure() throws Exception {
        Path target = temporaryDirectory.resolve("player.yml");
        Files.writeString(target, "state: old\n");
        IOException replacementFailure = new IOException("replacement failed");
        IOException cleanupFailure = new IOException("cleanup failed");

        IOException thrown = assertThrows(
                IOException.class,
                () -> AtomicYamlFiles.write(
                        target,
                        "state: new\n".getBytes(UTF_8),
                        (temporary, destination) -> { throw replacementFailure; },
                        temporary -> { throw cleanupFailure; }
                )
        );

        assertAll(
                () -> assertSame(replacementFailure, thrown),
                () -> assertArrayEquals(new Throwable[]{cleanupFailure}, thrown.getSuppressed()),
                () -> assertEquals("state: old\n", Files.readString(target))
        );
    }

    @Test
    void unsupportedAtomicMovePreservesExistingContentAndCleansUp() throws Exception {
        Path target = temporaryDirectory.resolve("player.yml");
        Files.writeString(target, "state: old\n");
        AtomicReference<Path> temporaryFile = new AtomicReference<>();

        IOException thrown = assertThrows(
                IOException.class,
                () -> AtomicYamlFiles.write(
                        target,
                        "state: new\n".getBytes(UTF_8),
                        (temporary, destination) -> {
                            temporaryFile.set(temporary);
                            throw new AtomicMoveNotSupportedException(
                                    temporary.toString(),
                                    destination.toString(),
                                    "not supported"
                            );
                        },
                        Files::deleteIfExists
                )
        );

        assertAll(
                () -> assertInstanceOf(AtomicMoveNotSupportedException.class, thrown.getCause()),
                () -> assertEquals("state: old\n", Files.readString(target)),
                () -> assertFalse(Files.exists(temporaryFile.get()))
        );
    }

    @Test
    void replacementFailurePreservesExistingContentAndCleansUp() throws Exception {
        Path target = temporaryDirectory.resolve("player.yml");
        Files.writeString(target, "state: old\n");
        AtomicReference<Path> temporaryFile = new AtomicReference<>();
        IOException replacementFailure = new IOException("replacement failed");

        IOException thrown = assertThrows(
                IOException.class,
                () -> AtomicYamlFiles.write(
                        target,
                        "state: new\n".getBytes(UTF_8),
                        (temporary, destination) -> {
                            temporaryFile.set(temporary);
                            throw replacementFailure;
                        },
                        Files::deleteIfExists
                )
        );

        assertAll(
                () -> assertSame(replacementFailure, thrown),
                () -> assertEquals("state: old\n", Files.readString(target)),
                () -> assertFalse(Files.exists(temporaryFile.get()))
        );
    }

    @Test
    void inputFailurePreservesExistingContentWithoutCreatingATemporaryFile() throws Exception {
        Path target = temporaryDirectory.resolve("config.yml");
        Files.writeString(target, "state: old\n");
        IOException inputFailure = new IOException("input failed");
        InputStream failingInput = new InputStream() {
            @Override
            public int read() throws IOException {
                throw inputFailure;
            }
        };

        IOException thrown = assertThrows(
                IOException.class,
                () -> AtomicYamlFiles.saveResource(failingInput, target)
        );

        long filesInDirectory;
        try(var files = Files.list(temporaryDirectory)) {
            filesInDirectory = files.count();
        }

        assertAll(
                () -> assertSame(inputFailure, thrown),
                () -> assertEquals("state: old\n", Files.readString(target)),
                () -> assertEquals(1, filesInDirectory)
        );
    }
}
