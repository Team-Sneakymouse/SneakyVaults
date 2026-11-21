package net.sneakymouse.sneakyvaults.types;

import net.sneakymouse.sneakyvaults.persistence.AtomicYamlFiles;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plain-data halves of vault persistence that run on the persistence queue. */
class VaultFilesTest {

    @TempDir
    Path directory;

    @Test
    void playerVaultWriteThenReadRoundTripsAndKeepsOtherData() throws IOException {
        File file = directory.resolve("player.yml").toFile();
        Files.writeString(file.toPath(), "template_vaults:\n  starter:\n    inventory: kept\nplayer_vaults:\n  2:\n    items: [other]\n    paperConverted: true\n");

        PlayerVault.write(file, 1, List.of("a", "b"));

        PlayerVault.Stored stored = PlayerVault.read(file, 1);
        assertEquals(List.of("a", "b"), stored.paperItems());
        assertEquals(List.of("other"), PlayerVault.read(file, 2).paperItems());
        assertEquals("kept", YamlConfiguration.loadConfiguration(file).getString("template_vaults.starter.inventory"));
    }

    @Test
    void playerVaultReadReturnsLegacyDataForUnconvertedVaults() throws IOException {
        File file = directory.resolve("player.yml").toFile();
        Files.writeString(file.toPath(), "player_vaults:\n  1: legacy-string\n");

        PlayerVault.Stored stored = PlayerVault.read(file, 1);

        assertNull(stored.paperItems());
        assertEquals("legacy-string", stored.legacy());
    }

    @Test
    void playerVaultReadIsNullForMissingFileOrVault() throws IOException {
        File file = directory.resolve("player.yml").toFile();
        assertNull(PlayerVault.read(file, 1));

        PlayerVault.write(file, 2, List.of("a"));
        assertNull(PlayerVault.read(file, 1));
    }

    @Test
    void corruptFileThrowsAndIsNotOverwritten() throws IOException {
        File file = directory.resolve("player.yml").toFile();
        Files.writeString(file.toPath(), "player_vaults: [unclosed\n");

        assertThrows(IOException.class, () -> PlayerVault.read(file, 1));
        assertThrows(IOException.class, () -> PlayerVault.write(file, 1, List.of("a")));
        assertEquals("player_vaults: [unclosed\n", Files.readString(file.toPath()));
    }

    @Test
    void sharedVaultWriteThenReadRoundTripsAndKeepsOtherData() throws IOException {
        File file = directory.resolve("business.yml").toFile();
        Files.writeString(file.toPath(), "name: Bakery\nmembers:\n  abc:\n    name: Ann\n");

        SharedVault.write(file, 3, List.of("x"));

        assertEquals(List.of("x"), SharedVault.read(file, 3));
        assertNull(SharedVault.read(file, 1));
        YamlConfiguration configuration = AtomicYamlFiles.load(file);
        assertEquals("Bakery", configuration.getString("name"));
        assertEquals("Ann", configuration.getString("members.abc.name"));
    }

    @Test
    void templateCopyReadsEmptyWhenAbsent() throws IOException {
        File file = directory.resolve("player.yml").toFile();
        assertEquals("", TemplateVault.readPlayerCopy(file, "starter"));
        assertTrue(!file.exists());
    }

    @Test
    void templateCopyWriteThenReadRoundTripsAndKeepsVaults() throws IOException {
        File file = directory.resolve("player.yml").toFile();
        PlayerVault.write(file, 1, List.of("a"));

        TemplateVault.writePlayerCopy(file, "starter", "saved");

        assertEquals("saved", TemplateVault.readPlayerCopy(file, "starter"));
        assertEquals(List.of("a"), PlayerVault.read(file, 1).paperItems());
    }
}
