package net.sneakymouse.sneakyvaults.types;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.persistence.AtomicYamlFiles;
import com.sneakyutils.util.TextUtility;
import com.sneakyutils.util.InventoryUtility;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

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
            File playerConfigFile = getPlayerConfigFile(player.getUniqueId().toString());
            YamlConfiguration configuration = loadPlayerConfiguration(playerConfigFile);

            ConfigurationSection section = configuration.getConfigurationSection("template_vaults");
            if(section == null)
                section = configuration.createSection("template_vaults");

            ConfigurationSection localVault = section.getConfigurationSection(this.vaultName);
            if(localVault == null)
                localVault = section.createSection(this.vaultName);

            localVault.set("inventory", InventoryUtility.inventoryToPaperYaml(closedInventory.getContents()));

            AtomicYamlFiles.save(configuration, playerConfigFile.toPath());
        } catch(IOException e){
            SneakyVaults.LOGGER.log(Level.SEVERE, "Failed to save template vault " + this.vaultName + " for " + player.getUniqueId(), e);
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThe vault could not be saved. A server maintainer has been notified."));
        }
    }

    public void startEditMode(Player player) throws IOException {
        Inventory templateInventory = getInventory(false);
        this.editMode = true;
        player.openInventory(templateInventory);
    }

    public void endEditMode(Inventory closedInventory) {
        this.editMode = false;

        ConfigurationSection vaultSection = templateSection.getConfigurationSection(this.vaultName);
        if(vaultSection == null) //This technically should not be possible
            vaultSection = templateSection.createSection(this.vaultName);

        vaultSection.set("inventory", InventoryUtility.inventoryToPaperYaml(closedInventory.getContents()));
        SneakyVaults.getInstance().saveConfig();
    }

    /**
     * Build the inventory a player sees for this template.
     * @throws IOException if the template or the player's saved copy cannot be read; nothing is opened or saved then
     * */
    public @NotNull Inventory getInventory(String playerUUID, boolean force) throws IOException {
        Inventory templateInventory = getInventory(true);

        if(force)
            return templateInventory;

        File playerConfigFile = getPlayerConfigFile(playerUUID);
        if(!playerConfigFile.isFile())
            return templateInventory;

        YamlConfiguration configuration = loadPlayerConfiguration(playerConfigFile);
        String inventory = configuration.getString("template_vaults." + this.vaultName + ".inventory", "");
        if(inventory.isEmpty())
            return templateInventory;

        templateInventory.clear();
        setContents(templateInventory, InventoryUtility.getSavedInventory(inventory));
        return templateInventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        try {
            return getInventory(false);
        } catch(IOException e) {
            throw new IllegalStateException("Template vault " + this.vaultName + " could not be read", e);
        }
    }

    private @NotNull Inventory getInventory(boolean playerFacing) throws IOException {
        String title = playerFacing ? "&eVault" : "&eTemplate Inventory";
        Inventory templateInventory = Bukkit.createInventory(this, this.size, TextUtility.INSTANCE.convertToComponent(title));

        String inventoryString = templateSection.getString(this.vaultName + ".inventory", "");
        if(!inventoryString.isEmpty())
            setContents(templateInventory, InventoryUtility.getSavedInventory(inventoryString));

        return templateInventory;
    }

    private void setContents(Inventory inventory, ItemStack[] contents) throws IOException {
        if(contents.length > inventory.getSize())
            throw new IOException("Saved inventory has " + contents.length + " slots but template " + this.vaultName + " has " + inventory.getSize());
        inventory.setContents(contents);
    }

    private static File getPlayerConfigFile(String playerUUID){
        return new File(SneakyVaults.getInstance().playerDataFolder, playerUUID + ".yml");
    }

    /** Strict load so a corrupt file is never overwritten with an empty one. */
    private static YamlConfiguration loadPlayerConfiguration(File file) throws IOException {
        YamlConfiguration configuration = new YamlConfiguration();
        if(!file.isFile())
            return configuration;
        try {
            configuration.load(file);
        } catch(InvalidConfigurationException e) {
            throw new IOException("Invalid YAML in " + file.getPath(), e);
        }
        return configuration;
    }

}
