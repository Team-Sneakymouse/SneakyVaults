package net.sneakymouse.sneakyvaults.types;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.persistence.AtomicYamlFiles;
import com.sneakyutils.util.InventoryUtility;
import com.sneakyutils.util.TextUtility;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * A character or business vault. There is one inventory per vault, so everyone who opens it
 * sees the same live contents and items cannot be duplicated between viewers.
 * Contents live in the owner's file under {@code vaults.<number>.items}.
 */
public class SharedVault implements InventoryHolder {

    public enum Kind {
        CHARACTER("Character Vault"),
        BUSINESS("Business Vault");

        private final String title;
        Kind(String title) { this.title = title; }
    }

    private static final int MINIMUM_SIZE = 9;
    private static final int MAXIMUM_SIZE = 54;

    private final Kind kind;
    private final String ownerId;
    private final int vaultNumber;
    private final File file;
    private final String title;
    private Inventory inventory;

    private SharedVault(Kind kind, String ownerId, int vaultNumber, File file, String title, ItemStack[] items) {
        this.kind = kind;
        this.ownerId = ownerId;
        this.vaultNumber = vaultNumber;
        this.file = file;
        this.title = title;
        this.inventory = Bukkit.createInventory(this, items.length, TextUtility.INSTANCE.convertToComponent(title));
        this.inventory.setContents(items);
    }

    /**
     * Load a vault from its owner's file.
     * @param size Size to create the vault with when it has no saved data, or 0 to only load existing vaults.
     *             Saved vaults keep their saved size if it is larger.
     * @return The vault, or null if it has no saved data and size is 0.
     * @throws IOException if the file or its item data cannot be read; nothing is created then.
     */
    public static @Nullable SharedVault load(Kind kind, String ownerId, int vaultNumber, File file, String name, int size) throws IOException {
        String title = "&e" + kind.title + " " + vaultNumber + (name == null || name.isBlank() ? "" : " &7- " + name);
        YamlConfiguration configuration = loadFile(file);
        String path = "vaults." + vaultNumber + ".items";

        if(!configuration.contains(path)) {
            if(size == 0) return null;
            validateSize(size, vaultNumber);
            return new SharedVault(kind, ownerId, vaultNumber, file, title, new ItemStack[size]);
        }

        List<String> encoded = configuration.getStringList(path);
        validateSize(encoded.size(), vaultNumber);
        ItemStack[] items;
        try {
            items = InventoryUtility.inventoryPaperFromBase64(encoded).toArray(new ItemStack[0]);
        } catch(RuntimeException exception) {
            throw new IOException("Vault " + vaultNumber + " in " + file.getName() + " contains invalid item data", exception);
        }

        SharedVault vault = new SharedVault(kind, ownerId, vaultNumber, file, title, items);
        vault.growTo(size);
        return vault;
    }

    /** Grow the vault to the given size, keeping items. Only done while nobody is looking at it. */
    public void growTo(int size) {
        if(size <= inventory.getSize() || size > MAXIMUM_SIZE || size % 9 != 0 || !inventory.getViewers().isEmpty())
            return;
        ItemStack[] items = inventory.getContents();
        inventory = Bukkit.createInventory(this, size, TextUtility.INSTANCE.convertToComponent(title));
        for(int i = 0; i < items.length; i++)
            inventory.setItem(i, items[i]);
    }

    public void save() throws IOException {
        List<String> encoded = InventoryUtility.inventoryPaperToBase64(inventory);
        updateFile(file, configuration -> configuration.set("vaults." + vaultNumber + ".items", encoded));
    }

    /** Close the vault for everyone looking at it. Closing saves it. */
    public void closeForAll() {
        for(HumanEntity viewer : new ArrayList<>(inventory.getViewers()))
            viewer.closeInventory();
    }

    public Kind getKind() { return kind; }
    public String getOwnerId() { return ownerId; }
    public int getVaultNumber() { return vaultNumber; }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }

    public @Nullable Location getDummyLocation() {
        if(Bukkit.getWorlds().isEmpty()) return null;
        return new Location(Bukkit.getWorlds().getFirst(), 0, 0, vaultNumber);
    }

    private static void validateSize(int size, int vaultNumber) throws IOException {
        if(size < MINIMUM_SIZE || size > MAXIMUM_SIZE || size % 9 != 0)
            throw new IOException("Vault " + vaultNumber + " has invalid inventory size " + size);
    }

    /** Strict load: a corrupt file throws instead of reading as empty, so it is never overwritten. */
    public static YamlConfiguration loadFile(File file) throws IOException {
        YamlConfiguration configuration = new YamlConfiguration();
        if(!file.isFile()) return configuration;
        try {
            configuration.load(file);
        } catch(InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + file.getPath(), exception);
        }
        return configuration;
    }

    /** Read the file, apply a change and write it back atomically. */
    public static void updateFile(File file, Consumer<YamlConfiguration> change) throws IOException {
        YamlConfiguration configuration = loadFile(file);
        change.accept(configuration);
        AtomicYamlFiles.save(configuration, file.toPath());
    }

    @Override
    public String toString() {
        return key(kind, ownerId, vaultNumber);
    }

    public static String key(Kind kind, String ownerId, int vaultNumber) {
        return kind + ":" + ownerId + ":" + vaultNumber;
    }

    /** Logs and reports a save failure; shared by the close listener and shutdown. */
    public void saveOrLog() {
        try {
            save();
        } catch(IOException exception) {
            SneakyVaults.LOGGER.log(Level.SEVERE, "Failed to save " + this, exception);
        }
    }
}
