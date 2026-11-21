package net.sneakymouse.sneakyvaults.types;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.persistence.AtomicYamlFiles;
import com.sneakyutils.util.TextUtility;
import com.sneakyutils.util.InventoryUtility;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
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

    public String getName() { return vaultName; }

    /** Persistence thread: write a player's copy of a template into their file, keeping everything else in it. */
    public static void writePlayerCopy(File playerConfigFile, String vaultName, String inventory) throws IOException {
        AtomicYamlFiles.update(playerConfigFile, configuration -> configuration.set("template_vaults." + vaultName + ".inventory", inventory));
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
     * Persistence thread: the player's saved copy of a template, or an empty string if they have none.
     * @throws IOException if the player's file is not valid YAML.
     */
    public static String readPlayerCopy(File playerConfigFile, String vaultName) throws IOException {
        return AtomicYamlFiles.load(playerConfigFile).getString("template_vaults." + vaultName + ".inventory", "");
    }

    /**
     * Server thread: build the inventory a player sees for this template.
     * @param savedCopy What {@link #readPlayerCopy} returned, or an empty string for a fresh copy of the template.
     * @throws IOException if the template or the player's saved copy cannot be decoded; nothing is opened or saved then
     * */
    public @NotNull Inventory buildPlayerInventory(String savedCopy) throws IOException {
        Inventory templateInventory = getInventory(true);
        if(savedCopy.isEmpty())
            return templateInventory;

        templateInventory.clear();
        setContents(templateInventory, InventoryUtility.getSavedInventory(savedCopy));
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

}
