package net.sneakymouse.sneakyvaults.types;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.utlitiy.ChatUtility;
import net.sneakymouse.sneakyvaults.utlitiy.InventoryUtility;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

public class TemplateVault implements InventoryHolder {

    private int size = -1;
    private boolean editMode = false;
    private String vaultName;
    private FileConfiguration config;
    private ConfigurationSection templateSection;

    public TemplateVault(String vaultName, int size, boolean editMode){
        this.size = size;
        this.editMode = editMode;
        this.vaultName = vaultName;

        config = SneakyVaults.getInstance().getConfig();

        templateSection = config.getConfigurationSection("template_vaults");
        if(templateSection == null)
            templateSection = config.createSection("template_vaults");
    }

    public boolean isEditMode(){ return editMode; }

    public int getSize() { return size; }

    public void onClosed(Player player, Inventory closedInventory){
        try {
            File playerConfigFile = new File(SneakyVaults.getInstance().playerDataFolder.getPath() + "/" + player.getUniqueId().toString() + ".yml");
            if(!playerConfigFile.exists()){
                if(!playerConfigFile.createNewFile()){
                    SneakyVaults.LOGGER.severe("Failed to create player config file!");
                    return;
                }
            }
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(playerConfigFile);
            ConfigurationSection section = configuration.getConfigurationSection("template_vaults");
            if(section == null)
                section = configuration.createSection("template_vaults");

            ConfigurationSection localVault = section.getConfigurationSection(this.vaultName);
            if(localVault == null)
                localVault = section.createSection(this.vaultName);

            String inventoryString = InventoryUtility.inventoryToBase64(this.size, closedInventory.getContents());
            localVault.set("inventory", inventoryString);

            configuration.save(playerConfigFile);
        } catch(IOException e){
            return;
        }
    }

    public void startEditMode(Player player){
        this.editMode = true;
        Inventory templateInventory = getInventory();
        player.openInventory(templateInventory);

    }
    public void endEditMode(Inventory closedInventory) {
        this.editMode = false;

        ConfigurationSection vaultSection = templateSection.getConfigurationSection(this.vaultName);
        if(vaultSection == null) //This technically should not be possible
            vaultSection = templateSection.createSection(this.vaultName);

        String inventoryString = InventoryUtility.inventoryToBase64(this.size, closedInventory.getContents());
        vaultSection.set("inventory", inventoryString);
        SneakyVaults.getInstance().saveConfig();
    }

    public @NotNull Inventory getInventory(String playerUUID, boolean force){
        Inventory templateInventory = Bukkit.createInventory(this, this.size, ChatUtility.convertToComponent("&eVault"));
        ConfigurationSection vaultSection = templateSection.getConfigurationSection(this.vaultName);
        if(vaultSection == null) //This technically should not be possible
            vaultSection = templateSection.createSection(this.vaultName);

        String inventoryString;

        inventoryString = vaultSection.getString("inventory", "");

        if(inventoryString.isEmpty()){
            return templateInventory;
        }
        ItemStack[] contents = InventoryUtility.getSavedInventory(inventoryString);

        templateInventory.setContents(contents);

        if(force)
            return templateInventory;

        try {
            File playerConfigFile = new File(SneakyVaults.getInstance().playerDataFolder.getPath() + "/" + playerUUID + ".yml");
            if(!playerConfigFile.exists()){
                if(!playerConfigFile.createNewFile()){
                    SneakyVaults.LOGGER.severe("Failed to create player config file!");
                    return templateInventory;
                }
            }
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(playerConfigFile);
            ConfigurationSection section = configuration.getConfigurationSection("template_vaults");
            if(section == null)
                section = configuration.createSection("template_vaults");

            ConfigurationSection localVault = section.getConfigurationSection(this.vaultName);
            if(localVault == null)
                localVault = section.createSection(this.vaultName);

            String inventory = localVault.getString("inventory", "");
            if(inventory.isEmpty()){
                return templateInventory;
            }

            configuration.save(playerConfigFile);

            contents = InventoryUtility.getSavedInventory(inventory);
            templateInventory.clear();
            templateInventory.setContents(contents);

            return templateInventory;
        } catch(IOException e){
            return templateInventory;
        }
    }

    @Override
    public @NotNull Inventory getInventory() {

        Inventory templateInventory = Bukkit.createInventory(this, this.size, ChatUtility.convertToComponent("&eTemplate Inventory"));
        ConfigurationSection vaultSection = templateSection.getConfigurationSection(this.vaultName);
        if(vaultSection == null) //This technically should not be possible
            vaultSection = templateSection.createSection(this.vaultName);

        String inventoryString = vaultSection.getString("inventory", "");

        if(inventoryString.isEmpty()){
            return templateInventory;
        }

        ItemStack[] contents = InventoryUtility.getSavedInventory(inventoryString);

        templateInventory.setContents(contents);

        return templateInventory;
    }


}
