package net.sneakymouse.sneakyvaults.types;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.utlitiy.ChatUtility;
import net.sneakymouse.sneakyvaults.utlitiy.InventoryUtility;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

public class PlayerVault implements InventoryHolder {

    private final String playerUUID;
    private Inventory inventory;
    private final int vaultNumber;

    private final File playerConfigFile;

    public PlayerVault(String playerUUID, int size, int vaultNumber, boolean force) throws IOException {
        this.playerUUID = playerUUID;
        this.inventory = Bukkit.createInventory(this, size, ChatUtility.convertToComponent("&ePlayer Vault"));
        this.vaultNumber = vaultNumber;

        playerConfigFile = new File(SneakyVaults.getInstance().playerDataFolder.getPath() + "/" + playerUUID + ".yml");
        if(!playerConfigFile.exists()){
            if(!playerConfigFile.createNewFile()){
                SneakyVaults.LOGGER.severe("Failed to create player config file!");
                return;
            }
            return; //If the config didn't exist then we have no reason to try to load the vault inventory
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(playerConfigFile);
        ConfigurationSection vaults = configuration.getConfigurationSection("player_vaults");
        if(vaults == null){
            configuration.createSection("player_vaults");
            return; //No Vault Section? Then No Vaults to load!
        }

        configuration.save(playerConfigFile);

        try {
            if(!force)
                loadFromConfig();
        } catch(IOException e){
            e.printStackTrace();
            SneakyVaults.LOGGER.severe("Failed to create player config file!");
        }
    }

    private void loadFromConfig() throws IOException {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(playerConfigFile);
        ConfigurationSection vaults = configuration.getConfigurationSection("player_vaults");

        if(vaults == null) return;

        String vaultInventory = vaults.getString("" + vaultNumber);
        if(vaultInventory == null) return;

        ItemStack[] items = InventoryUtility.getSavedInventory(vaultInventory);
        if(items.length > this.inventory.getSize()) {
            SneakyVaults.LOGGER.warning("Saved inventory is larger then localized inventory size? This is strange but will be fixed!");
            if(items.length % 9 != 0) {
                SneakyVaults.LOGGER.severe("Error: Saved inventory size is not a multiple of 9. This should be impossible!");
                return;
            }
            this.inventory = Bukkit.createInventory(this, items.length, ChatUtility.convertToComponent("&ePlayer Vault"));
        }

        this.inventory.setContents(items);

        configuration.save(playerConfigFile);
    }

    public void saveVault() throws IOException {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(playerConfigFile);
        ConfigurationSection vaults = configuration.getConfigurationSection("player_vaults");
        if(vaults == null) {
            vaults = configuration.createSection("player_vaults");
        }

        String itemEncoded = InventoryUtility.inventoryToBase64(this.getInventory(false));
        vaults.set("" + this.vaultNumber, itemEncoded);

        configuration.save(playerConfigFile);
    }

    public void fixInventorySize(){
        int maxSize = SneakyVaults.getInstance().vaultManager.getMaxVaultSize(this.playerUUID);
        if(maxSize > this.inventory.getSize()){

            ItemStack[] items = this.inventory.getContents();
            this.inventory = Bukkit.createInventory(this, maxSize, ChatUtility.convertToComponent("&ePlayer Vault"));

            for(int i = 0; i < items.length; i++){
                ItemStack item = items[i];
                if(item == null) continue;
                this.inventory.setItem(i, item);
            }
        }

        try {
            saveVault();
        } catch(IOException ignored) {}
    }

    public String getOwner(){ return this.playerUUID; }
    @Override
    public @NotNull Inventory getInventory() {
        return this.getInventory(true);
    }

    public @NotNull Inventory getInventory(boolean fix) {

        try{
            if(fix){
                loadFromConfig();
                fixInventorySize();
            }
        } catch(IOException ignored){}

        return this.inventory;
    }
}
