package net.sneakymouse.sneakyvaults.managers;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.persistence.AtomicYamlFiles;
import net.sneakymouse.sneakyvaults.persistence.PendingLoads;
import net.sneakymouse.sneakyvaults.persistence.PersistenceQueue;
import net.sneakymouse.sneakyvaults.types.Business;
import net.sneakymouse.sneakyvaults.types.SharedVault;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/**
 * Keeps character and business vaults that are currently open, and the list of businesses.
 * A vault stays cached while someone is looking at it, so all viewers share one inventory.
 *
 * <p>Server thread only. Files are read and written on the persistence queue; the futures returned
 * here complete on the server thread.
 */
public class SharedVaultManager {

    public static final String CHARACTER_MAX_PREFIX = SneakyVaults.IDENTIFIER + ".character.max.";
    public static final String CHARACTER_SLOTS_PREFIX = SneakyVaults.IDENTIFIER + ".character.slots.";

    private final File characterFolder;
    private final File businessFolder;
    private final Map<String, SharedVault> openVaults = new HashMap<>();
    private final Map<String, Business> businesses = new TreeMap<>();
    private final PendingLoads<String, Optional<List<String>>> loads;

    public SharedVaultManager(File dataFolder) {
        characterFolder = new File(dataFolder, "CharacterData");
        businessFolder = new File(dataFolder, "Businesses");
        loads = new PendingLoads<>(SneakyVaults.getInstance().serverThread);
        if(!characterFolder.isDirectory() && !characterFolder.mkdirs())
            SneakyVaults.LOGGER.severe("Failed to create CharacterData folder!");
        if(!businessFolder.isDirectory() && !businessFolder.mkdirs())
            SneakyVaults.LOGGER.severe("Failed to create Businesses folder!");
        loadBusinesses();
    }

    /** Runs once while the plugin enables, before any player can open a vault. */
    private void loadBusinesses() {
        File[] files = businessFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if(files == null) return;
        for(File file : files) {
            try {
                Business business = Business.load(file);
                businesses.put(business.getId(), business);
            } catch(IOException exception) {
                SneakyVaults.LOGGER.log(Level.SEVERE, "Could not load business " + file.getName() + "; it is unavailable until fixed", exception);
            }
        }
    }

    // ---- Character vaults ----

    public File getCharacterFile(String characterUUID) {
        return new File(characterFolder, characterUUID + ".yml");
    }

    /**
     * Get a character vault, creating it at the player's permitted size if it does not exist yet.
     * @return Completes on the server thread with the vault, or null if the player may not have that
     *         vault number or has no slots permission. Completes exceptionally if the vault cannot be read.
     */
    public CompletableFuture<SharedVault> getCharacterVault(Player player, String characterUUID, String characterName, int vaultNumber) {
        int maxVaults = VaultManager.maxVaultsFromPermissions(player, CHARACTER_MAX_PREFIX);
        if(vaultNumber < 1 || vaultNumber > maxVaults) return CompletableFuture.completedFuture(null);

        int size = VaultManager.maxSlotsFromPermissions(player, CHARACTER_SLOTS_PREFIX);
        if(size < 9) return CompletableFuture.completedFuture(null);

        File file = getCharacterFile(characterUUID);
        String playerUUID = player.getUniqueId().toString();
        return load(SharedVault.Kind.CHARACTER, characterUUID, vaultNumber, file, characterName, size).thenApply(vault -> {
            if(vault != null) {
                vault.growTo(size);
                // Remember who owns the character so admins can find the file later.
                SneakyVaults.getInstance().reportFailure(
                        SneakyVaults.getInstance().persistence.run(() -> AtomicYamlFiles.update(file, configuration -> {
                            configuration.set("player", playerUUID);
                            configuration.set("name", characterName);
                        })),
                        "Could not record the owner of " + file.getName(), null, "");
            }
            return vault;
        });
    }

    /** Admin access: only opens vaults that already have saved data. Completes with null otherwise. */
    public CompletableFuture<SharedVault> getExistingCharacterVault(String characterUUID, int vaultNumber) {
        return load(SharedVault.Kind.CHARACTER, characterUUID, vaultNumber, getCharacterFile(characterUUID), null, 0);
    }

    // ---- Business vaults ----

    public @Nullable Business getBusiness(String id) {
        return businesses.get(id.toLowerCase());
    }

    public List<Business> getBusinesses() {
        return new ArrayList<>(businesses.values());
    }

    public List<Business> getBusinessesOf(String characterUUID) {
        return businesses.values().stream().filter(b -> b.isMember(characterUUID)).toList();
    }

    /**
     * Create a business and queue writing it. If the write fails, the business is removed again.
     * @return Completes on the server thread once the business is on disk, or null if one with that id already exists.
     */
    public @Nullable CompletableFuture<Void> createBusiness(String id, String name) {
        if(businesses.containsKey(id)) return null;
        Business business = new Business(id, name, new File(businessFolder, id + ".yml"));
        businesses.put(id, business);
        return business.save().whenCompleteAsync((ignored, error) -> {
            if(error != null) businesses.remove(id, business);
        }, SneakyVaults.getInstance().serverThread);
    }

    /**
     * Remove a business. Its file is moved to {@code Businesses/deleted/} rather than erased,
     * after the saves of its vaults that closing them queued. If the move fails, the business comes back.
     * @return Completes on the server thread once the file has been moved.
     */
    public CompletableFuture<Void> deleteBusiness(Business business) {
        closeBusinessVaults(business, null);
        openVaults.values().removeIf(vault -> isVaultOf(vault, business) && vault.getInventory().getViewers().isEmpty());
        businesses.remove(business.getId(), business);

        File source = business.getFile();
        File deletedFolder = new File(businessFolder, "deleted");
        File target = new File(deletedFolder, business.getId() + "-" + System.currentTimeMillis() + ".yml");
        return SneakyVaults.getInstance().persistence.run(() -> {
            Files.createDirectories(deletedFolder.toPath());
            if(source.exists())
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        }).whenCompleteAsync((ignored, error) -> {
            if(error != null) businesses.putIfAbsent(business.getId(), business);
        }, SneakyVaults.getInstance().serverThread);
    }

    /**
     * Get a business vault, creating it at the business's size if it does not exist yet.
     * @param create False for admin access that only opens vaults with saved data.
     * @return Completes on the server thread with the vault, or null if it has no saved data and create is false.
     */
    public CompletableFuture<SharedVault> getBusinessVault(Business business, int vaultNumber, boolean create) {
        int size = create ? business.getVaultSize() : 0;
        return load(SharedVault.Kind.BUSINESS, business.getId(), vaultNumber, business.getFile(), business.getName(), size)
                .thenApply(vault -> {
                    if(vault != null)
                        vault.growTo(business.getVaultSize());
                    return vault;
                });
    }

    /** True while the business is still registered, so a vault that finished loading late may be opened. */
    public boolean isCurrent(Business business) {
        return businesses.get(business.getId()) == business;
    }

    /**
     * Close a business's open vaults for everyone, or only for one member's player.
     */
    public void closeBusinessVaults(Business business, @Nullable Player onlyFor) {
        for(SharedVault vault : new ArrayList<>(openVaults.values())) {
            if(!isVaultOf(vault, business)) continue;
            if(onlyFor == null)
                vault.closeForAll();
            else if(vault.getInventory().getViewers().contains(onlyFor))
                onlyFor.closeInventory();
        }
    }

    private static boolean isVaultOf(SharedVault vault, Business business) {
        return vault.getKind() == SharedVault.Kind.BUSINESS && vault.getOwnerId().equals(business.getId());
    }

    // ---- Shared ----

    /**
     * The cached vault, or a load of it. Reads run on the persistence queue after any queued save of the
     * same vault, so a vault reopened right after closing sees its saved contents.
     */
    private CompletableFuture<SharedVault> load(SharedVault.Kind kind, String ownerId, int vaultNumber, File file, @Nullable String name, int size) {
        String key = SharedVault.key(kind, ownerId, vaultNumber);
        SharedVault cached = openVaults.get(key);
        if(cached != null) return CompletableFuture.completedFuture(cached);

        PersistenceQueue persistence = SneakyVaults.getInstance().persistence;
        return loads.load(key, () -> persistence.submit(() -> Optional.ofNullable(SharedVault.read(file, vaultNumber))))
                .thenApply(encoded -> adopt(key, kind, ownerId, vaultNumber, file, name, encoded.orElse(null), size));
    }

    /**
     * Server thread: the cached vault if another request built it first, otherwise a new one from the
     * loaded data, which is cached so every viewer shares it.
     */
    private @Nullable SharedVault adopt(String key, SharedVault.Kind kind, String ownerId, int vaultNumber, File file,
                                        @Nullable String name, @Nullable List<String> encoded, int size) {
        SharedVault cached = openVaults.get(key);
        if(cached != null) return cached;

        SharedVault vault;
        try {
            vault = SharedVault.create(kind, ownerId, vaultNumber, file, name, encoded, size);
        } catch(IOException exception) {
            throw new CompletionException(exception);
        }
        if(vault != null)
            openVaults.put(key, vault);
        return vault;
    }

    /** Forget a loaded vault that ended up not being opened. */
    public void release(SharedVault vault) {
        if(vault.getInventory().getViewers().isEmpty())
            openVaults.remove(vault.toString(), vault);
    }

    /**
     * Called when someone closes a vault. Queues saving it, and forgets it once the last viewer has left.
     * @return Completes when the save is on disk, or exceptionally if it failed.
     */
    public CompletableFuture<Void> onClose(SharedVault vault) {
        try {
            return vault.save();
        } finally {
            // The closing player is still counted as a viewer during the close event.
            if(vault.getInventory().getViewers().size() <= 1)
                openVaults.remove(vault.toString(), vault);
        }
    }

    /** Queue saving every cached vault; used on disable. */
    public void saveAll() {
        openVaults.values().forEach(vault -> SneakyVaults.getInstance().reportSaveFailure(vault.save(), vault.toString(), null));
    }
}
