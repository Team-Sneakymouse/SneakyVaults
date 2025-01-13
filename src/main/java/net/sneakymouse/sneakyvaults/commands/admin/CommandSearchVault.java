package net.sneakymouse.sneakyvaults.commands.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandAdminBase;
import net.sneakymouse.sneakyvaults.utlitiy.ChatUtility;
import net.sneakymouse.sneakyvaults.utlitiy.InventoryUtility;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

public class CommandSearchVault extends CommandAdminBase {

    private static final Logger log = LoggerFactory.getLogger(CommandSearchVault.class);
    private final File vaultData;


    public static class SearchResultData {
        private String playerUUID;
        private int vaultNumber;
        private String message;


        public SearchResultData(final String playerUUID, final int vaultNumber) {
            this.playerUUID = playerUUID;
            this.vaultNumber = vaultNumber;
        }

        public SearchResultData(final String playerUUID, final int vaultNumber, final String message) {
            this.playerUUID = playerUUID;
            this.vaultNumber = vaultNumber;
            this.message = message;
        }

        public String getPlayerUUID() {
            return playerUUID;
        }

        public void setPlayerUUID(String playerUUID) {
            this.playerUUID = playerUUID;
        }

        public int getVaultNumber() {
            return vaultNumber;
        }

        public void setVaultNumber(int vaultNumber) {
            this.vaultNumber = vaultNumber;
        }
    }

    public CommandSearchVault() {
        super("searchvault");
        vaultData = SneakyVaults.getInstance().playerDataFolder;

        this.usageMessage = "/searchvault [name/id] [Item Name / Item ID]";
    }


    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {

        if(args.length < 2) {
            sender.sendMessage(ChatUtility.convertToComponent("&cInvalid Usage: " + this.usageMessage));
            return false;
        }

        sender.sendMessage(ChatUtility.convertToComponent("&eSearching for item..."));
        boolean isName = args[0].equalsIgnoreCase("name");
        StringBuilder builder = new StringBuilder();

        for(int i = 1; i < args.length; i++){
            builder.append(args[i]).append(" ");
        }
        String itemData = builder.substring(0, builder.length()-1);

        searchPlayerVaults(
                itemData,
                isName,
                (result) -> sender.sendMessage(ChatUtility.convertToComponent(result.message).clickEvent(ClickEvent.clickEvent(
                        ClickEvent.Action.SUGGEST_COMMAND, "/peekvault " + result.playerUUID + " " + result.vaultNumber
                ))),
                () -> sender.sendMessage(ChatUtility.convertToComponent("&eFinished Searching for item!"))
        );

        return false;
    }

    private void searchPlayerVaults(String data, boolean isName, Consumer<SearchResultData> onResultfound, Runnable onComplete) {
        if(!vaultData.exists() || !vaultData.isDirectory()) {
            onComplete.run();
            return;
        }
        File[] playerDataFiles = vaultData.listFiles();

        if(playerDataFiles == null) {
            onComplete.run();
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(SneakyVaults.getInstance(), () -> {
            for(File playerDataFile : playerDataFiles) {
                YamlConfiguration configuration = YamlConfiguration.loadConfiguration(playerDataFile);
                ConfigurationSection vaults = configuration.getConfigurationSection("player_vaults");
                if(vaults == null) continue;

                for(String vault : vaults.getKeys(false)) {
                    if(!vaults.getBoolean(vault + ".paperConverted")) {
                        onResultfound.accept(
                                new SearchResultData(
                                        playerDataFile.getName().replace(".yml", ""),
                                        Integer.parseInt(vault),
                                        "Vault " + playerDataFile.getName() + " is not PaperConverted.. Skipping File!")
                        );
                        break;
                    }

                    String vaultInventory = vaults.getString(vault);
                    if(vaultInventory == null) continue;

                    List<String> vaultItems = vaults.getStringList(vault + ".items");
                    List<ItemStack> items = InventoryUtility.inventoryPaperFromBase64(vaultItems);

                    for(ItemStack item : items){
                        if(item == null) continue;
                        String key;
                        if(isName) {
                            if (item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
                                Component displayName = item.getItemMeta().displayName();
                                key =  PlainTextComponentSerializer.plainText().serialize(displayName);
                            } else {
                                // Return the type name of the item if there's no custom display name
                                key = item.getType().toString().toLowerCase().replace('_', ' ');
                            }
                        }
                        else {
                            if(item.getType().isLegacy()){
                                key = item.getType().toString();
                            } else {
                                key = item.getType().getKey().toString();
                            }
                        }

                        if(key.toLowerCase().contains(data.toLowerCase())) {
                            Bukkit.getScheduler().runTask(SneakyVaults.getInstance(), () -> onResultfound.accept(
                                    new SearchResultData(
                                            playerDataFile.getName().replace(".yml", ""),
                                            Integer.parseInt(vault),
                                            "&eUUID: &c" + playerDataFile.getName().replace(".yml", "")
                                                    + "&e | Vault: &6" + vault
                                    )
                            ));
                            break;
                        }
                    }
                }
            }
            Bukkit.getScheduler().runTask(SneakyVaults.getInstance(), onComplete);
        });
    }
}
