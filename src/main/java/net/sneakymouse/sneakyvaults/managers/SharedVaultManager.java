package net.sneakymouse.sneakyvaults.managers;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.persistence.AtomicYamlFiles;
import net.sneakymouse.sneakyvaults.persistence.VaultStore;
import net.sneakymouse.sneakyvaults.types.Business;
import net.sneakymouse.sneakyvaults.types.SharedVault;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Keeps the loaded character and business vaults, and the list of businesses.
 * There is one inventory per vault, shared by all its viewers. A vault is held in a {@link VaultStore}:
 * it stays loaded until its last close was written and nobody is looking at it.
 *
 * <p>Server thread only. Files are read and written on the persistence queue. Futures of loads and opens
 * complete on the server thread; futures of saves (such as {@code onClose}) complete on the persistence
 * thread when the write finishes.
 */
public class SharedVaultManager {

    public static final String CHARACTER_MAX_PREFIX = SneakyVaults.IDENTIFIER + ".character.max.";
    public static final String CHARACTER_SLOTS_PREFIX = SneakyVaults.IDENTIFIER + ".character.slots.";

    private final File characterFolder;
    private final File businessFolder;
    private final VaultStore<List<String>, SharedVault> vaults;
    private final Map<String, Business> businesses = new TreeMap<>();
    private final Set<String> busyIds = new HashSet<>();

    public SharedVaultManager(File dataFolder) {
        characterFolder = new File(dataFolder, "CharacterData");
        businessFolder = new File(dataFolder, "Businesses");
        SneakyVaults plugin = SneakyVaults.getInstance();
        vaults = new VaultStore<>(plugin.persistence, plugin.serverThread, SharedVault::isViewed, true);
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
        return load(SharedVault.Kind.CHARACTER, characterUUID, vaultNumber, file, characterName, size, () -> true).thenApply(vault -> {
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
        return load(SharedVault.Kind.CHARACTER, characterUUID, vaultNumber, getCharacterFile(characterUUID), null, 0, () -> true);
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
     * once its vaults are written: closing them queues their saves, and the move waits for those. The id
     * stays reserved until the move is done. If a vault write or the move fails, the business comes back
     * unchanged, and an unwritten vault stays loaded with its contents.
     * @return Completes on the server thread once the file has been moved.
     */
    public CompletableFuture<Void> deleteBusiness(Business business) {
        String id = business.getId();
        String prefix = SharedVault.keyPrefix(SharedVault.Kind.BUSINESS, id);
        Predicate<String> ofBusiness = key -> key.startsWith(prefix);
        closeBusinessVaults(business, null);
        CompletableFuture<Void> vaultsWritten = vaults.whenWritten(ofBusiness);
        businesses.remove(id, business);
        busyIds.add(id);

        File source = business.getFile();
        File deletedFolder = new File(businessFolder, "deleted");
        File target = new File(deletedFolder, id + "-" + System.currentTimeMillis() + ".yml");
        return vaultsWritten
                .thenCompose(ignored -> SneakyVaults.getInstance().persistence.run(() -> {
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
                    vaults.removeCleanIf(ofBusiness);
                }, SneakyVaults.getInstance().serverThread);
    }

    /**
     * Get a business vault, creating it at the business's size if it does not exist yet.
     * @param create False for admin access that only opens vaults with saved data.
     * @return Completes on the server thread with the vault, or null if it has no saved data and create is
     *         false, or if the business was deleted while the vault was read.
     */
    public CompletableFuture<SharedVault> getBusinessVault(Business business, int vaultNumber, boolean create) {
        int size = create ? business.getVaultSize() : 0;
        // A read that finishes after the business was deleted must not be kept for a later one with the same id.
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
        for(SharedVault vault : vaults.values()) {
            if(vault.getKind() != SharedVault.Kind.BUSINESS || !vault.getOwnerId().equals(business.getId())) continue;
            if(onlyFor == null)
                vault.closeForAll();
            else if(vault.getInventory().getViewers().contains(onlyFor))
                onlyFor.closeInventory();
        }
    }

    // ---- Shared ----

    /**
     * @param stillValid Checked on the server thread once the data has been read; when false, the result is
     *                   null and nothing is kept.
     */
    private CompletableFuture<SharedVault> load(SharedVault.Kind kind, String ownerId, int vaultNumber, File file, @Nullable String name,
                                                int size, BooleanSupplier stillValid) {
        return vaults.load(SharedVault.key(kind, ownerId, vaultNumber),
                () -> SharedVault.read(file, vaultNumber),
                encoded -> stillValid.getAsBoolean() ? SharedVault.create(kind, ownerId, vaultNumber, file, name, encoded, size) : null);
    }

    /** Forget a loaded vault that ended up not being opened, unless it holds contents not yet on disk. */
    public void release(SharedVault vault) {
        vaults.release(vault.toString());
    }

    /**
     * Called when someone closes a vault: queue writing its contents. The store keeps it loaded until that
     * write succeeded and nobody is looking at it any more.
     * @return Completes when the contents are on disk, or exceptionally if the write failed.
     */
    public CompletableFuture<Void> onClose(SharedVault vault) {
        return vaults.save(vault.toString(), vault.snapshotWrite());
    }

    /**
     * Queue writing every vault whose contents may not be on disk (open, or its last write failed); used on
     * disable. A closed vault of a deleted business is already written, so this never recreates its file.
     */
    public void saveAll() {
        vaults.forEachNeedingSave((key, vault) ->
                SneakyVaults.getInstance().reportSaveFailure(vaults.save(key, vault.snapshotWrite()), key, null));
    }
}
