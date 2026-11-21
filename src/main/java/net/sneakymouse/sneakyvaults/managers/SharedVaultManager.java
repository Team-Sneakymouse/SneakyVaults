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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
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
    private final Set<String> busyIds = new HashSet<>();
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

    /** True while a create or delete of this id is still being written; the id can't be used until then. */
    public boolean isBusy(String id) {
        return busyIds.contains(id);
    }

    /**
     * Create a business and queue writing it. It is only registered once it is on disk, and its id is
     * reserved until then, so nothing can change it while the write is pending.
     * @return Completes on the server thread once the business is on disk and registered,
     *         or null if the id already exists or is busy.
     */
    public @Nullable CompletableFuture<Void> createBusiness(String id, String name) {
        if(businesses.containsKey(id) || busyIds.contains(id)) return null;
        Business business = new Business(id, name, new File(businessFolder, id + ".yml"));
        busyIds.add(id);
        return business.save().whenCompleteAsync((ignored, error) -> {
            busyIds.remove(id);
            if(error == null) businesses.put(id, business);
        }, SneakyVaults.getInstance().serverThread);
    }

    /**
     * Remove a business. Its file is moved to {@code Businesses/deleted/} rather than erased, but only
     * once every save its vaults queued when they were closed has reached disk. The id stays reserved
     * until the move is done. If a save or the move fails, the business comes back unchanged.
     * @return Completes on the server thread once the file has been moved.
     */
    public CompletableFuture<Void> deleteBusiness(Business business) {
        String id = business.getId();
        closeBusinessVaults(business, null);
        CompletableFuture<?>[] saves = openVaults.values().stream()
                .filter(vault -> isVaultOf(vault, business))
                .map(SharedVault::lastSave)
                .toArray(CompletableFuture[]::new);
        businesses.remove(id, business);
        busyIds.add(id);

        File source = business.getFile();
        File deletedFolder = new File(businessFolder, "deleted");
        File target = new File(deletedFolder, id + "-" + System.currentTimeMillis() + ".yml");
        PersistenceQueue persistence = SneakyVaults.getInstance().persistence;
        return CompletableFuture.allOf(saves)
                .thenCompose(ignored -> persistence.run(() -> {
                    Files.createDirectories(deletedFolder.toPath());
                    if(source.exists())
                        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                }))
                .whenCompleteAsync((ignored, error) -> {
                    busyIds.remove(id);
                    if(error != null) {
                        businesses.putIfAbsent(id, business);
                        return;
                    }
                    openVaults.values().removeIf(vault -> isVaultOf(vault, business) && vault.getInventory().getViewers().isEmpty());
                }, SneakyVaults.getInstance().serverThread);
    }

    /**
     * Get a business vault, creating it at the business's size if it does not exist yet.
     * @param create False for admin access that only opens vaults with saved data.
     * @return Completes on the server thread with the vault, or null if it has no saved data and create is false.
     */
    public CompletableFuture<SharedVault> getBusinessVault(Business business, int vaultNumber, boolean create) {
        int size = create ? business.getVaultSize() : 0;
        // A read that finishes after the business was deleted must not be cached for a later one with the same id.
        return load(SharedVault.Kind.BUSINESS, business.getId(), vaultNumber, business.getFile(), business.getName(), size, () -> isCurrent(business))
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
        return load(kind, ownerId, vaultNumber, file, name, size, () -> true);
    }

    /**
     * @param stillValid Checked on the server thread once the data has been read; when false, the
     *                   result is null and nothing is cached.
     */
    private CompletableFuture<SharedVault> load(SharedVault.Kind kind, String ownerId, int vaultNumber, File file, @Nullable String name,
                                                int size, BooleanSupplier stillValid) {
        String key = SharedVault.key(kind, ownerId, vaultNumber);
        SharedVault cached = openVaults.get(key);
        if(cached != null) return CompletableFuture.completedFuture(cached);

        PersistenceQueue persistence = SneakyVaults.getInstance().persistence;
        return loads.load(key, () -> persistence.submit(() -> Optional.ofNullable(SharedVault.read(file, vaultNumber))))
                .thenApply(encoded -> stillValid.getAsBoolean()
                        ? adopt(key, kind, ownerId, vaultNumber, file, name, encoded.orElse(null), size)
                        : null);
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

    /** Forget a loaded vault that ended up not being opened, unless it holds changes not yet on disk. */
    public void release(SharedVault vault) {
        if(vault.isSafeToForget())
            openVaults.remove(vault.toString(), vault);
    }

    /**
     * Called when someone closes a vault. Queues saving it, and forgets it once that save is on disk and
     * nobody is looking at it any more.
     *
     * <p>Forgetting waits for the save: if it failed, the cached inventory stays the authoritative copy
     * (the next close or shutdown saves it again) instead of a reopen reading the older file. It also
     * waits for the viewer check: Paper closes a player's current view before opening another, so a
     * player reopening the same vault would otherwise leave it open but uncached.
     * @return Completes when the save is on disk, or exceptionally if it failed.
     */
    public CompletableFuture<Void> onClose(SharedVault vault) {
        CompletableFuture<Void> save = vault.save();
        save.whenCompleteAsync((ignored, error) -> {
            if(vault.lastSave() == save && vault.isSafeToForget())
                openVaults.remove(vault.toString(), vault);
        }, SneakyVaults.getInstance().serverThread);
        return save;
    }

    /**
     * Queue saving every cached vault; used on disable.
     *
     * <p>Vaults of a business that is deleted or being deleted are skipped when their close-save went
     * through: those contents are already on disk (or archived), and a write queued after the archive
     * would recreate the business file. If their last save failed, the delete aborts and keeps the file,
     * so they are saved again.
     */
    public void saveAll() {
        for(SharedVault vault : openVaults.values()) {
            boolean deleted = vault.getKind() == SharedVault.Kind.BUSINESS && !businesses.containsKey(vault.getOwnerId());
            if(deleted && !vault.lastSave().isCompletedExceptionally())
                continue;
            SneakyVaults.getInstance().reportSaveFailure(vault.save(), vault.toString(), null);
        }
    }
}
