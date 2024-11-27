package net.sneakymouse.sneakyvaults.commands.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandAdminBase;
import net.sneakymouse.sneakyvaults.utlitiy.ChatUtility;
import net.sneakymouse.sneakyvaults.utlitiy.InventoryUtility;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class CommandForceConversion extends CommandAdminBase {

    public CommandForceConversion() {
        super("forcevaultconversions");
        this.description = "Force convert all player vaults to paper serialization";
        this.usageMessage = "/forcevaultconversions";
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {

        File file = SneakyVaults.getInstance().playerDataFolder;
        if(!file.exists() || !file.isDirectory()) {
            sender.sendMessage(ChatUtility.convertToComponent("&4No player data files found!"));
            return false;
        }
        File[] playerDataFiles = file.listFiles();

        if(playerDataFiles == null) {
            sender.sendMessage(ChatUtility.convertToComponent("&4No player data files found!"));
            return false;
        }

        for(File playerDataFile : playerDataFiles) {
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(playerDataFile);
            ConfigurationSection vaults = configuration.getConfigurationSection("player_vaults");
            if(vaults == null) continue;

            for(String vault : vaults.getKeys(false)) {
                if(vaults.getBoolean(vault + ".paperConverted")) continue;

                String vaultInventory = vaults.getString("" + vault);
                if(vaultInventory == null) continue;

                Inventory inventory = Bukkit.createInventory(null, 9);

                ItemStack[] items = InventoryUtility.getSavedInventory(vaultInventory);
                if(items.length > inventory.getSize()) {
                    SneakyVaults.LOGGER.warning("Saved inventory is larger then localized inventory size? This is strange but will be fixed!");
                    if(items.length % 9 != 0) {
                        SneakyVaults.LOGGER.severe("Error: Saved inventory size is not a multiple of 9. This should be impossible!");
                        continue;
                    }
                    inventory = Bukkit.createInventory(null, items.length, ChatUtility.convertToComponent("&ePlayer Vault"));
                }

                inventory.setContents(items);

                List<String> itemEncoded = InventoryUtility.inventoryPaperToBase64(inventory);
                vaults.set(vault  + ".items", itemEncoded);
                vaults.set(vault + ".paperConverted", true);

                try {
                    configuration.save(playerDataFile);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }


            }
        }

        sender.sendMessage(
                Component.text("Finished converting all player data files to Paper Serialization")
                        .color(NamedTextColor.GREEN)
        );

        return false;
    }
}
