package net.sneakymouse.sneakyvaults.commands.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandAdminBase;
import net.sneakymouse.sneakyvaults.persistence.AtomicYamlFiles;
import net.sneakymouse.sneakyvaults.persistence.PersistenceQueue;
import com.sneakyutils.util.TextUtility;
import com.sneakyutils.util.InventoryUtility;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Files are read and written on the persistence queue; items are decoded and re-encoded on the server thread.
 */
public class CommandForceConversion extends CommandAdminBase {

    /** A vault still in the old format: its old inventory string, and the Paper encoding once converted. */
    private record Legacy(String encoded, List<String> converted) {}

    public CommandForceConversion() {
        super("forcevaultconversions");
        this.description = "Force convert all player vaults to paper serialization";
        this.usageMessage = "/forcevaultconversions";
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {

        SneakyVaults plugin = SneakyVaults.getInstance();
        File folder = plugin.playerDataFolder;
        // Files or vaults that were skipped or could not be written, counted across all three steps.
        AtomicInteger problems = new AtomicInteger();
        plugin.persistence.submit(() -> {
                    // Skip leftover temporary files of interrupted writes (".<name>.yml.<n>.tmp").
                    File[] playerDataFiles = folder.listFiles((dir, name) -> name.endsWith(".yml") && !name.startsWith("."));
                    return playerDataFiles == null ? null : findLegacyVaults(playerDataFiles, problems);
                })
                .thenApplyAsync(legacy -> legacy == null ? null : convert(legacy, problems), plugin.serverThread)
                .thenCompose(converted -> converted == null
                        ? CompletableFuture.<Boolean>completedFuture(false)
                        : plugin.persistence.submit(() -> { writeConverted(converted, problems); return true; }))
                .whenCompleteAsync((found, error) -> {
                    if(error == null && !found) {
                        sender.sendMessage(TextUtility.INSTANCE.convertToComponent("&4No player data files found!"));
                        return;
                    }
                    if(error != null) {
                        SneakyVaults.LOGGER.log(Level.SEVERE, "Vault conversion failed", PersistenceQueue.cause(error));
                        sender.sendMessage(TextUtility.INSTANCE.convertToComponent("&cVault conversion failed. Check the server log."));
                        return;
                    }
                    if(problems.get() > 0) {
                        sender.sendMessage(TextUtility.INSTANCE.convertToComponent(
                                "&c" + problems.get() + " player data files or vaults were skipped or could not be converted. Check the server log."));
                        return;
                    }
                    sender.sendMessage(
                            Component.text("Finished converting all player data files to Paper Serialization")
                                    .color(NamedTextColor.GREEN)
                    );
                }, plugin.serverThread);

        return false;
    }

    /** Persistence thread: the old-format vaults in each file. */
    private static Map<File, Map<String, String>> findLegacyVaults(File[] playerDataFiles, AtomicInteger problems) {
        Map<File, Map<String, String>> legacy = new LinkedHashMap<>();
        for(File playerDataFile : playerDataFiles) {
            YamlConfiguration configuration;
            try {
                configuration = AtomicYamlFiles.load(playerDataFile);
            } catch(IOException exception) {
                SneakyVaults.LOGGER.log(Level.WARNING, "Skipping unreadable " + playerDataFile.getName() + " for conversion", exception);
                problems.incrementAndGet();
                continue;
            }
            ConfigurationSection vaults = configuration.getConfigurationSection("player_vaults");
            if(vaults == null) continue;

            for(String vault : vaults.getKeys(false)) {
                if(vaults.getBoolean(vault + ".paperConverted")) continue;

                String vaultInventory = vaults.getString(vault);
                if(vaultInventory == null) continue;
                if(vaultInventory.isBlank()) {
                    // Opening such a vault is refused too; don't turn it into an empty one.
                    SneakyVaults.LOGGER.warning("Skipping vault " + vault + " in " + playerDataFile.getName() + ": it has no readable inventory data");
                    problems.incrementAndGet();
                    continue;
                }
                legacy.computeIfAbsent(playerDataFile, ignored -> new LinkedHashMap<>()).put(vault, vaultInventory);
            }
        }
        return legacy;
    }

    /** Server thread: decode each old-format vault and encode it in the Paper format. */
    private static Map<File, Map<String, Legacy>> convert(Map<File, Map<String, String>> legacy, AtomicInteger problems) {
        Map<File, Map<String, Legacy>> converted = new LinkedHashMap<>();
        legacy.forEach((playerDataFile, vaults) -> vaults.forEach((vault, vaultInventory) -> {
            Inventory inventory = Bukkit.createInventory(null, 9);

            ItemStack[] items;
            try {
                items = InventoryUtility.getSavedInventory(vaultInventory);
            } catch (IOException e) {
                SneakyVaults.LOGGER.warning("Failed to decode vault inventory for conversion: " + e.getMessage());
                problems.incrementAndGet();
                return;
            }
            if(items.length > inventory.getSize()) {
                SneakyVaults.LOGGER.warning("Saved inventory is larger then localized inventory size? This is strange but will be fixed!");
                if(items.length % 9 != 0) {
                    SneakyVaults.LOGGER.severe("Error: Saved inventory size is not a multiple of 9. This should be impossible!");
                    problems.incrementAndGet();
                    return;
                }
                inventory = Bukkit.createInventory(null, items.length, TextUtility.INSTANCE.convertToComponent("&ePlayer Vault"));
            }

            inventory.setContents(items);
            List<String> itemEncoded = InventoryUtility.inventoryPaperToBase64(inventory);
            converted.computeIfAbsent(playerDataFile, ignored -> new LinkedHashMap<>()).put(vault, new Legacy(vaultInventory, itemEncoded));
        }));
        return converted;
    }

    /**
     * Persistence thread: write the converted vaults. A vault that was saved since it was read is no
     * longer in the old format and is left alone, so a newer save is never replaced.
     * Files that could not be written are counted in problems.
     */
    private static void writeConverted(Map<File, Map<String, Legacy>> converted, AtomicInteger problems) {
        for(Map.Entry<File, Map<String, Legacy>> entry : converted.entrySet()) {
            try {
                AtomicYamlFiles.update(entry.getKey(), configuration -> {
                    ConfigurationSection vaults = configuration.getConfigurationSection("player_vaults");
                    if(vaults == null) return;
                    entry.getValue().forEach((vault, legacy) -> {
                        if(vaults.getBoolean(vault + ".paperConverted") || !legacy.encoded().equals(vaults.getString(vault))) return;
                        vaults.set(vault + ".items", legacy.converted());
                        vaults.set(vault + ".paperConverted", true);
                    });
                });
            } catch(IOException exception) {
                SneakyVaults.LOGGER.log(Level.SEVERE, "Could not write converted vaults to " + entry.getKey().getName(), exception);
                problems.incrementAndGet();
            }
        }
    }
}
