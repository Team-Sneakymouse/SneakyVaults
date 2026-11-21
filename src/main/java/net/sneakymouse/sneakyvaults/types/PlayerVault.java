package net.sneakymouse.sneakyvaults.types;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.persistence.AtomicYamlFiles;
import com.sneakyutils.util.TextUtility;
import com.sneakyutils.util.InventoryUtility;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A player's global vault. Once loaded it stays cached in the VaultManager, and its inventory is the
 * authoritative copy: it is written to disk on every close and is never re-read while cached.
 *
 * <p>Disk access is split from Bukkit access: {@link #read} and {@link #write} run on the persistence
 * queue with plain data, and {@link #create} and {@link #save} run on the server thread.
 * */
public class PlayerVault implements InventoryHolder {

    private static final int MINIMUM_SIZE = 9;
    private static final int MAXIMUM_SIZE = 54;
    private static final String TITLE = "&ePlayer Vault";

    /**
     * A vault's saved data as read from disk, still encoded.
     * @param paperItems Paper-serialized items, or null for a vault in the old format.
     * @param legacy The old-format inventory string, used when paperItems is null.
     */
    public record Stored(@Nullable List<String> paperItems, @Nullable String legacy) {}

    private final String playerUUID;
    private Inventory inventory;
    private final int vaultNumber;
    public boolean isOpened = false;
    private final File playerConfigFile;

    private PlayerVault(String playerUUID, int vaultNumber, File playerConfigFile, ItemStack[] items) {
        this.playerUUID = playerUUID;
        this.vaultNumber = vaultNumber;
        this.playerConfigFile = playerConfigFile;
        this.inventory = Bukkit.createInventory(this, items.length, TextUtility.INSTANCE.convertToComponent(TITLE));
        this.inventory.setContents(items);
    }

    public static File fileFor(String playerUUID) {
        return new File(SneakyVaults.getInstance().playerDataFolder, playerUUID + ".yml");
    }

    /**
     * Persistence thread: read a vault's saved data.
     * @return The data, or null if the vault has never been saved.
     * @throws IOException if the file is not valid YAML.
     */
    public static @Nullable Stored read(File file, int vaultNumber) throws IOException {
        YamlConfiguration configuration = AtomicYamlFiles.load(file);
        ConfigurationSection vaults = configuration.getConfigurationSection("player_vaults");
        String path = Integer.toString(vaultNumber);
        if(vaults == null || !vaults.contains(path))
            return null;

        if(vaults.getBoolean(path + ".paperConverted"))
            return new Stored(vaults.getStringList(path + ".items"), null);
        return new Stored(null, vaults.getString(path));
    }

    /**
     * Persistence thread: write a vault's items into its owner's file, keeping everything else in it.
     */
    public static void write(File file, int vaultNumber, List<String> encodedItems) throws IOException {
        AtomicYamlFiles.update(file, configuration -> {
            ConfigurationSection vaults = configuration.getConfigurationSection("player_vaults");
            if(vaults == null)
                vaults = configuration.createSection("player_vaults");
            vaults.set(vaultNumber + ".items", encodedItems);
            vaults.set(vaultNumber + ".paperConverted", true);
        });
    }

    /**
     * Server thread: build a vault from the data {@link #read} returned.
     * @param size Size for a vault with no saved data, or 0 to return null for it.
     *             A saved vault grows to this size, but keeps its saved size if that is larger.
     * @throws IOException if the saved item data cannot be decoded; nothing is created then.
     */
    public static @Nullable PlayerVault create(String playerUUID, int vaultNumber, @Nullable Stored stored, int size) throws IOException {
        File file = fileFor(playerUUID);
        if(stored == null) {
            if(size == 0) return null;
            validateStoredSize(size, vaultNumber);
            return new PlayerVault(playerUUID, vaultNumber, file, new ItemStack[size]);
        }

        PlayerVault vault = new PlayerVault(playerUUID, vaultNumber, file, decode(stored, vaultNumber));
        vault.growTo(size);
        return vault;
    }

    private static ItemStack[] decode(Stored stored, int vaultNumber) throws IOException {
        ItemStack[] items;
        if(stored.paperItems() != null) {
            validateStoredSize(stored.paperItems().size(), vaultNumber);
            try {
                items = InventoryUtility.inventoryPaperFromBase64(stored.paperItems()).toArray(new ItemStack[0]);
            } catch(RuntimeException exception) {
                throw new IOException("Vault " + vaultNumber + " contains invalid Paper item data", exception);
            }
        } else {
            String encodedInventory = stored.legacy();
            if(encodedInventory == null || encodedInventory.isBlank())
                throw new IOException("Vault " + vaultNumber + " has no readable inventory data");

            items = InventoryUtility.getSavedInventoryStrict(encodedInventory);
            validateStoredSize(items.length, vaultNumber);
        }
        return items;
    }

    private static void validateStoredSize(int size, int vaultNumber) throws IOException {
        if(size < MINIMUM_SIZE || size > MAXIMUM_SIZE || size % 9 != 0)
            throw new IOException("Vault " + vaultNumber + " has invalid inventory size " + size);
    }

    /**
     * Server thread: snapshot the contents and queue writing them to disk.
     * @return Completes when the contents are on disk, or exceptionally if the write failed.
     */
    public CompletableFuture<Void> save() {
        List<String> encodedItems = InventoryUtility.inventoryPaperToBase64(this.inventory);
        File file = this.playerConfigFile;
        int number = this.vaultNumber;
        return SneakyVaults.getInstance().persistence.run(() -> write(file, number, encodedItems));
    }

    /**
     * Grow the vault to the given size, keeping items. Only done while nobody is looking at it.
     * The larger size is saved the next time the vault is closed.
     * */
    public void growTo(int size){
        if(size <= inventory.getSize() || size > MAXIMUM_SIZE || size % 9 != 0 || !inventory.getViewers().isEmpty())
            return;

        ItemStack[] items = this.inventory.getContents();
        this.inventory = Bukkit.createInventory(this, size, TextUtility.INSTANCE.convertToComponent(TITLE));
        for(int i = 0; i < items.length; i++){
            ItemStack item = items[i];
            if(item == null) continue;
            this.inventory.setItem(i, item);
        }
    }

    public String getOwner(){ return this.playerUUID; }
    public int getVaultNumber(){ return this.vaultNumber; }

    @Override
    public @NotNull Inventory getInventory() {
        return this.inventory;
    }

    public Location getDummyLocation() {
        if(Bukkit.getWorlds().isEmpty()) return null;
        return new Location(
                Bukkit.getWorlds().getFirst(),
                0,
                0,
                this.vaultNumber);
    }
}
