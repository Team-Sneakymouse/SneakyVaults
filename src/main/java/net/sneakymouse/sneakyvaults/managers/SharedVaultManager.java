package net.sneakymouse.sneakyvaults.managers;

import net.sneakymouse.sneakyvaults.SneakyVaults;
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
import java.util.TreeMap;
import java.util.logging.Level;

/**
 * Keeps character and business vaults that are currently open, and the list of businesses.
 * A vault stays cached while someone is looking at it, so all viewers share one inventory.
 */
public class SharedVaultManager {

    public static final String CHARACTER_MAX_PREFIX = SneakyVaults.IDENTIFIER + ".character.max.";
    public static final String CHARACTER_SLOTS_PREFIX = SneakyVaults.IDENTIFIER + ".character.slots.";

    private final File characterFolder;
    private final File businessFolder;
    private final Map<String, SharedVault> openVaults = new HashMap<>();
    private final Map<String, Business> businesses = new TreeMap<>();

    public SharedVaultManager(File dataFolder) {
        characterFolder = new File(dataFolder, "CharacterData");
        businessFolder = new File(dataFolder, "Businesses");
        if(!characterFolder.isDirectory() && !characterFolder.mkdirs())
            SneakyVaults.LOGGER.severe("Failed to create CharacterData folder!");
        if(!businessFolder.isDirectory() && !businessFolder.mkdirs())
            SneakyVaults.LOGGER.severe("Failed to create Businesses folder!");
        loadBusinesses();
    }

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
     * @return The vault, or null if the player may not have that vault number or no slots permission.
     */
    public @Nullable SharedVault getCharacterVault(Player player, String characterUUID, String characterName, int vaultNumber) throws IOException {
        int maxVaults = VaultManager.maxVaultsFromPermissions(player, CHARACTER_MAX_PREFIX);
        if(vaultNumber < 1 || vaultNumber > maxVaults) return null;

        int size = VaultManager.maxSlotsFromPermissions(player, CHARACTER_SLOTS_PREFIX);
        if(size < 9) return null;

        SharedVault vault = getOrLoad(SharedVault.Kind.CHARACTER, characterUUID, vaultNumber, getCharacterFile(characterUUID), characterName, size);
        if(vault != null) {
            vault.growTo(size);
            // Remember who owns the character so admins can find the file later.
            File file = getCharacterFile(characterUUID);
            SharedVault.updateFile(file, configuration -> {
                configuration.set("player", player.getUniqueId().toString());
                configuration.set("name", characterName);
            });
        }
        return vault;
    }

    /** Admin access: only opens vaults that already have saved data. */
    public @Nullable SharedVault getExistingCharacterVault(String characterUUID, int vaultNumber) throws IOException {
        return getOrLoad(SharedVault.Kind.CHARACTER, characterUUID, vaultNumber, getCharacterFile(characterUUID), null, 0);
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

    /** @return The new business, or null if one with that id already exists. */
    public @Nullable Business createBusiness(String id, String name) throws IOException {
        if(businesses.containsKey(id)) return null;
        Business business = new Business(id, name, new File(businessFolder, id + ".yml"));
        business.save();
        businesses.put(id, business);
        return business;
    }

    /**
     * Remove a business. Its file is moved to {@code Businesses/deleted/} rather than erased.
     */
    public void deleteBusiness(Business business) throws IOException {
        closeBusinessVaults(business, null);
        File deletedFolder = new File(businessFolder, "deleted");
        Files.createDirectories(deletedFolder.toPath());
        File target = new File(deletedFolder, business.getId() + "-" + System.currentTimeMillis() + ".yml");
        if(business.getFile().exists())
            Files.move(business.getFile().toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        businesses.remove(business.getId());
    }

    /**
     * Get a business vault, creating it at the business's size if it does not exist yet.
     * @param create False for admin access that only opens vaults with saved data.
     */
    public @Nullable SharedVault getBusinessVault(Business business, int vaultNumber, boolean create) throws IOException {
        int size = create ? business.getVaultSize() : 0;
        SharedVault vault = getOrLoad(SharedVault.Kind.BUSINESS, business.getId(), vaultNumber, business.getFile(), business.getName(), size);
        if(vault != null)
            vault.growTo(business.getVaultSize());
        return vault;
    }

    /**
     * Close a business's open vaults for everyone, or only for one member's player.
     */
    public void closeBusinessVaults(Business business, @Nullable Player onlyFor) {
        for(SharedVault vault : new ArrayList<>(openVaults.values())) {
            if(vault.getKind() != SharedVault.Kind.BUSINESS || !vault.getOwnerId().equals(business.getId())) continue;
            if(onlyFor == null)
                vault.closeForAll();
            else if(vault.getInventory().getViewers().contains(onlyFor))
                onlyFor.closeInventory();
        }
    }

    // ---- Shared ----

    private @Nullable SharedVault getOrLoad(SharedVault.Kind kind, String ownerId, int vaultNumber, File file, @Nullable String name, int size) throws IOException {
        String key = SharedVault.key(kind, ownerId, vaultNumber);
        SharedVault cached = openVaults.get(key);
        if(cached != null) return cached;

        SharedVault vault = SharedVault.load(kind, ownerId, vaultNumber, file, name, size);
        if(vault != null)
            openVaults.put(key, vault);
        return vault;
    }

    /**
     * Called when someone closes a vault. Saves it, and forgets it once the last viewer has left.
     */
    public void onClose(SharedVault vault) throws IOException {
        try {
            vault.save();
        } finally {
            // The closing player is still counted as a viewer during the close event.
            if(vault.getInventory().getViewers().size() <= 1)
                openVaults.remove(vault.toString());
        }
    }

    public void saveAll() {
        openVaults.values().forEach(SharedVault::saveOrLog);
    }
}
