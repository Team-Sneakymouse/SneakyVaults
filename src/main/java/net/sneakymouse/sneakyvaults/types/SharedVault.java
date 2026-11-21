package net.sneakymouse.sneakyvaults.types;

import net.sneakymouse.sneakyvaults.persistence.AtomicYamlFiles;
import net.sneakymouse.sneakyvaults.persistence.PersistenceQueue;
import com.sneakyutils.util.InventoryUtility;
import com.sneakyutils.util.TextUtility;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

/**
 * A character or business vault. There is one inventory per vault, so everyone who opens it
 * sees the same live contents and items cannot be duplicated between viewers.
 * Contents live in the owner's file under {@code vaults.<number>.items}.
 *
 * <p>Disk access is split from Bukkit access: {@link #read} and {@link #write} run on the persistence
 * queue with plain data, and {@link #create} and {@link #snapshotWrite} run on the server thread.
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
     * Persistence thread: read a vault's encoded items from its owner's file.
     * @return The encoded items, or null if the vault has never been saved.
     * @throws IOException if the file is not valid YAML.
     */
    public static @Nullable List<String> read(File file, int vaultNumber) throws IOException {
        YamlConfiguration configuration = AtomicYamlFiles.load(file);
        String path = "vaults." + vaultNumber + ".items";
        return configuration.contains(path) ? configuration.getStringList(path) : null;
    }

    /** Persistence thread: write a vault's encoded items, keeping everything else in the file. */
    public static void write(File file, int vaultNumber, List<String> encodedItems) throws IOException {
        AtomicYamlFiles.update(file, configuration -> configuration.set("vaults." + vaultNumber + ".items", encodedItems));
    }

    /**
     * Server thread: build a vault from the items {@link #read} returned.
     * @param size Size to create the vault with when it has no saved data, or 0 to only load existing vaults.
     *             Saved vaults keep their saved size if it is larger.
     * @return The vault, or null if it has no saved data and size is 0.
     * @throws IOException if the item data cannot be decoded; nothing is created then.
     */
    public static @Nullable SharedVault create(Kind kind, String ownerId, int vaultNumber, File file, @Nullable String name,
                                               @Nullable List<String> encoded, int size) throws IOException {
        String title = "&e" + kind.title + " " + vaultNumber + (name == null || name.isBlank() ? "" : " &7- " + name);

        if(encoded == null) {
            if(size == 0) return null;
            validateSize(size, vaultNumber);
            return new SharedVault(kind, ownerId, vaultNumber, file, title, new ItemStack[size]);
        }

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

    /**
     * Server thread: snapshot the contents now and return the write of that snapshot, for the persistence queue.
     */
    public PersistenceQueue.VoidTask snapshotWrite() {
        List<String> encoded = InventoryUtility.inventoryPaperToBase64(inventory);
        File target = file;
        int number = vaultNumber;
        return () -> write(target, number, encoded);
    }

    public boolean isViewed() {
        return !inventory.getViewers().isEmpty();
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

    @Override
    public String toString() {
        return key(kind, ownerId, vaultNumber);
    }

    public static String key(Kind kind, String ownerId, int vaultNumber) {
        return keyPrefix(kind, ownerId) + vaultNumber;
    }

    /** The start of every vault key of one owner. */
    public static String keyPrefix(Kind kind, String ownerId) {
        return kind + ":" + ownerId + ":";
    }
}
