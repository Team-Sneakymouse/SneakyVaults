package net.sneakymouse.sneakyvaults.types;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.utlitiy.ChatUtility;
import net.sneakymouse.sneakyvaults.utlitiy.InventoryUtility;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Class containing major information about items, size, and the vault number of a players vault.
 * */
public class PlayerVault implements InventoryHolder {

    private final String playerUUID;
    private Inventory inventory;
    private final int vaultNumber;
    public boolean isOpened = false;
    private final File playerConfigFile;

    /**
     * Create a new player vault based off the owning players UUID.
     * @param playerUUID UUID of the player who owns the player vault.
     * @param size The size of the vault to be created. Must be a multiple of 9.
     * @param vaultNumber The vault number relating to this vault.
     * @param force Should we force create a new inventory? Set to <b>true</b> to create an empty inventory. False will load from config if exists
     * */
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

        if(vaults.getBoolean(vaultNumber + ".paperConverted")) {
            List<String> vaultItems = vaults.getStringList(vaultNumber + ".items");

            List<ItemStack> items = InventoryUtility.inventoryPaperFromBase64(vaultItems);
            ItemStack[] itemStacks = items.toArray(new ItemStack[0]);

            if(itemStacks.length > this.inventory.getSize()) {
                SneakyVaults.LOGGER.warning("Saved inventory is larger then localized inventory size? This is strange but will be fixed!");
                if(itemStacks.length % 9 != 0) {
                    SneakyVaults.LOGGER.severe("Error: Saved inventory size is not a multiple of 9. This should be impossible!");
                    return;
                }
                this.inventory = Bukkit.createInventory(this, itemStacks.length, ChatUtility.convertToComponent("&ePlayer Vault"));
            }

            this.inventory.setContents(itemStacks);
        }
        else {
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
        }

        configuration.save(playerConfigFile);
    }

    public void saveVault() throws IOException {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(playerConfigFile);
        ConfigurationSection vaults = configuration.getConfigurationSection("player_vaults");
        if(vaults == null) {
            vaults = configuration.createSection("player_vaults");
        }

        List<String> itemEncoded = InventoryUtility.inventoryPaperToBase64(this.getInventory(false));
        vaults.set(this.vaultNumber  + ".items", itemEncoded);
        vaults.set(this.vaultNumber + ".paperConverted", true);

        configuration.save(playerConfigFile);
    }

    /**
     * Fix the config inventory size.
     * This will only run if the new size is larger than the config saved size.
     * It will convert the inventory to expected size and place all the old items into the new inventory, then save the config.
     * */
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

    /**
     * Get the playervault inventory.
     * @param fix Should we re-set the size of the inventory.
     * */
    public @NotNull Inventory getInventory(boolean fix) {

        try{
            if(fix){
                loadFromConfig();
                fixInventorySize();
            }
        } catch(IOException ignored){}

        return this.inventory;
    }

    public Location getDummyLocation() {
        Player player = Bukkit.getPlayer(UUID.fromString(this.playerUUID));
        if(player == null) return null;
        return new Location(
                player.getWorld(),
                0,
                0,
                this.vaultNumber);
    }
}
