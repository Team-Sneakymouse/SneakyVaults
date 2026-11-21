package net.sneakymouse.sneakyvaults.managers;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.persistence.PersistenceQueue;
import net.sneakymouse.sneakyvaults.persistence.VaultStore;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import net.sneakymouse.sneakyvaults.types.TemplateVault;
import com.sneakyutils.util.InventoryUtility;
import com.sneakyutils.util.TextUtility;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static net.sneakymouse.sneakyvaults.SneakyVaults.IDENTIFIER;

/**
 * Global vaults and players' template copies. Both are held in a {@link VaultStore}: once loaded, the
 * in-memory inventory is the authoritative copy. Global vaults stay loaded for the session; a template
 * copy is dropped once its last close was written and the player isn't looking at it.
 *
 * <p>Server thread only. Files are read and written on the persistence queue. Futures of loads and opens
 * complete on the server thread; futures of saves (such as {@code onClose}) complete on the persistence
 * thread when the write finishes.
 */
public class VaultManager {

    public Map<String, String> vaultSizeSession;

    public Map<String, TemplateVault> templateVaults;

    /** Keyed by {@code <player uuid>:<vault number>}. */
    private final VaultStore<PlayerVault.Stored, PlayerVault> playerVaults;
    /** A player's copy of a template, keyed by player and template name (see {@link #copyKey}). */
    private final VaultStore<String, Inventory> templateCopies;

    public VaultManager(){
        // Read by the async chat listener's quick check, so it must be thread-safe.
        vaultSizeSession = new ConcurrentHashMap<>();
        templateVaults = new HashMap<>();
        SneakyVaults plugin = SneakyVaults.getInstance();
        playerVaults = new VaultStore<>(plugin.persistence, plugin.serverThread,
                vault -> vault.isOpened || !vault.getInventory().getViewers().isEmpty(), false);
        templateCopies = new VaultStore<>(plugin.persistence, plugin.serverThread,
                inventory -> !inventory.getViewers().isEmpty(), true);
    }

    /**
     * Queue writing every vault and template copy whose contents may not be on disk (open, or its last
     * write failed); used on disable.
     * */
    public void saveAllVaults(){
        playerVaults.forEachNeedingSave((key, vault) -> SneakyVaults.getInstance().reportSaveFailure(
                playerVaults.save(key, vault.snapshotWrite()),
                "vault " + vault.getVaultNumber() + " for " + vault.getOwner(),
                null
        ));
        templateCopies.forEachNeedingSave((key, inventory) -> SneakyVaults.getInstance().reportSaveFailure(
                templateCopies.save(key, copyWrite(key, inventory)),
                "template copy " + key.replace('\0', ' '),
                null
        ));
    }

    /**
     * Get the max number of allowed vaults a player has permission to own.
     * @param playerUUID UUID of the player to check max vaults
     * @return Maximum number of vaults a player has permission to own.
     * */
    public int getMaxAllowedVaults(String playerUUID){
        Player player = Bukkit.getPlayer(UUID.fromString(playerUUID));
        if(player == null) return -1;
        return maxVaultsFromPermissions(player, IDENTIFIER + ".max.");
    }

    /**
     * Highest N among the player's granted {@code <prefix>N} permissions, or 0 if none.
     * */
    public static int maxVaultsFromPermissions(Player player, String prefix){
        return player.getEffectivePermissions().stream()
                .filter(p -> p.getValue() && p.getPermission().startsWith(prefix))
                .mapToInt(p -> parseVaultCount(p.getPermission().substring(prefix.length())))
                .max().orElse(0);
    }

    /**
     * Parse the suffix of a sneakyvaults.max.N node. Malformed or negative suffixes grant nothing.
     */
    private static int parseVaultCount(String suffix){
        try {
            return Math.max(0, Integer.parseInt(suffix));
        } catch(NumberFormatException e){
            return 0;
        }
    }

    /**
     * Gets the maximum vault size a player has permission to have
     * @param playerUUID UUID of the player to check the max vault size of.
     * @return max number of slots for the vault. Will be a multiple of 9
     * */
    public int getMaxVaultSize(String playerUUID){
        Player player = Bukkit.getPlayer(UUID.fromString(playerUUID));
        if(player == null) return -1;
        return maxSlotsFromPermissions(player, IDENTIFIER + ".slots.");
    }

    /**
     * Largest size among the player's {@code <prefix>9 .. <prefix>54} permissions, or -1 if none.
     * */
    public static int maxSlotsFromPermissions(Player player, String prefix){
        for(int i = 6; i >= 1; i--){
            if(player.hasPermission(prefix + 9*i)){
                return 9*i;
            }
        }
        return -1;
    }

    private static String vaultKey(String playerUUID, int vaultNumber) {
        return playerUUID + ":" + vaultNumber;
    }

    /**
     * Get an existing vault without consulting owner permissions or creating storage.
     * @return Completes on the server thread with the vault, or null if it was never saved.
     *         Completes exceptionally if it cannot be read.
     */
    public CompletableFuture<PlayerVault> getExistingPlayerVault(@NotNull String playerUUID, int vaultNumber) {
        return loadVault(playerUUID, vaultNumber, 0);
    }

    /**
     * Get a players player vault based off UUID and Vault Number.
     * This will create a new vault if one does not exist already.
     * @param playerUUID UUID of the player to get the vault of
     * @param vaultNumber The vault number to get.
     * @return Completes on the server thread with the vault, or null if the player may not have that vault.
     *         Completes exceptionally if it cannot be read.
     * @see PlayerVault
     * */
    public CompletableFuture<PlayerVault> getPlayerVault(@NotNull String playerUUID, int vaultNumber){
        int maxVaults = getMaxAllowedVaults(playerUUID);
        if(maxVaults < 0 || vaultNumber < 1 || vaultNumber > maxVaults)
            return CompletableFuture.completedFuture(null);

        PlayerVault loadedVault = playerVaults.get(vaultKey(playerUUID, vaultNumber));
        if(loadedVault != null)
            return CompletableFuture.completedFuture(loadedVault);

        int maxSize = getMaxVaultSize(playerUUID);
        if(maxSize < 9)
            return CompletableFuture.completedFuture(null);

        return loadVault(playerUUID, vaultNumber, maxSize);
    }

    /**
     * The loaded vault, or a load of it from the player's file.
     * @param createSize Size for a vault that was never saved, or 0 to complete with null for it.
     */
    private CompletableFuture<PlayerVault> loadVault(@NotNull String playerUUID, int vaultNumber, int createSize) {
        File file = PlayerVault.fileFor(playerUUID);
        return playerVaults.load(vaultKey(playerUUID, vaultNumber), () -> PlayerVault.read(file, vaultNumber), stored -> {
            long building = SneakyVaults.getInstance().probe.start();
            try {
                return PlayerVault.create(playerUUID, vaultNumber, stored, createSize);
            } finally {
                SneakyVaults.getInstance().probe.end("vault.player.load", building);
            }
        });
    }

    /**
     * Called when a global vault is closed: queue writing its contents.
     * @return Completes when written, or exceptionally if the write failed.
     */
    public CompletableFuture<Void> onClose(PlayerVault vault) {
        return playerVaults.save(vaultKey(vault.getOwner(), vault.getVaultNumber()), vault.snapshotWrite());
    }

    /**
     * Create a new template vault.
     * @param vaultName The name of the vault that will be created
     * @return True if a new vault was created. False if the vault already exists, or something went wrong.
     * */
    public boolean createTemplateVault(@NotNull String vaultName){
        FileConfiguration config = SneakyVaults.getInstance().getConfig();

        ConfigurationSection section = config.getConfigurationSection("template_vaults");
        if(section == null)
            section = config.createSection("template_vaults");

        ConfigurationSection vaultSection = section.getConfigurationSection(vaultName);
        if(vaultSection == null){
            section.createSection(vaultName);
            SneakyVaults.getInstance().saveConfig();
            return true;
        }
        SneakyVaults.getInstance().saveConfig();
        return false;
    }

    private TemplateVault getOrCreateTemplateVault(String vaultName){
        if(templateVaults.containsKey(vaultName))
            return templateVaults.get(vaultName);

        FileConfiguration config = SneakyVaults.getInstance().getConfig();

        ConfigurationSection section = config.getConfigurationSection("template_vaults");
        if(section == null)
            return null;

        ConfigurationSection vaultSection = section.getConfigurationSection(vaultName);
        if(vaultSection == null)
            return null;

        int vaultSize = vaultSection.getInt("size", -1);
        if(vaultSize == -1)
            return null;

        // Cache it so every caller shares one edit-mode flag.
        TemplateVault vault = new TemplateVault(vaultName, vaultSize, false);
        templateVaults.put(vaultName, vault);
        return vault;
    }

    /**
     * Opens the template inventory for the player.
     * @param playerUUID The player UUID to open the inventory for
     * @param vaultName The name of the template vault to open
     * @param toForce True if you want it to create a new vault inventory each time. False will open the last vault the player opened
     * @return Completes on the server thread with true if the vault was opened, false otherwise
     * */
    public CompletableFuture<Boolean> openTemplateVault(String playerUUID, String vaultName, boolean toForce){
        long opened = SneakyVaults.getInstance().probe.start();
        try {
            return openTemplate(playerUUID, vaultName, toForce);
        } finally {
            SneakyVaults.getInstance().probe.end("vault.template.open", opened);
        }
    }

    private CompletableFuture<Boolean> openTemplate(String playerUUID, String vaultName, boolean toForce){
        TemplateVault vault = getOrCreateTemplateVault(vaultName);
        if(vault == null || vault.isEditMode()) return CompletableFuture.completedFuture(false);

        Player player = Bukkit.getPlayer(UUID.fromString(playerUUID));
        if(player == null) return CompletableFuture.completedFuture(false);
        long request = SneakyVaults.getInstance().beginOpen(player);
        String key = copyKey(playerUUID, vaultName);

        if(toForce) {
            // A fresh copy replaces the player's copy. One still open is closed first, which saves it.
            if(isViewingTemplate(player, vaultName))
                player.closeInventory();
            Inventory fresh;
            try {
                fresh = vault.buildPlayerInventory("");
            } catch(IOException e) {
                SneakyVaults.LOGGER.log(Level.SEVERE, "Refusing to open unreadable template vault " + vaultName + " for " + playerUUID, e);
                return CompletableFuture.completedFuture(false);
            }
            // Keep the fresh copy only once it is open: if another plugin cancels the open, an unused fresh
            // copy would otherwise replace the player's real one and be saved over it on a later open.
            if(!showTemplate(player, fresh))
                return CompletableFuture.completedFuture(false);
            templateCopies.put(key, fresh);
            return CompletableFuture.completedFuture(true);
        }

        // The player's copy: the loaded one if any, otherwise read from their file (or a fresh copy if they
        // have none). A copy still open is the same inventory, so reopening it is safe.
        File file = PlayerVault.fileFor(playerUUID);
        return templateCopies.load(key, () -> TemplateVault.readPlayerCopy(file, vaultName), savedCopy -> {
            // The template may have been deleted, replaced or put in edit mode while the copy was read.
            if(vault.isEditMode() || templateVaults.get(vaultName) != vault) return null;
            long loading = SneakyVaults.getInstance().probe.start();
            try {
                return vault.buildPlayerInventory(savedCopy == null ? "" : savedCopy);
            } finally {
                SneakyVaults.getInstance().probe.end("vault.template.load", loading);
            }
        }).handle((copy, error) -> {
            if(error != null) {
                SneakyVaults.LOGGER.log(Level.SEVERE, "Refusing to open unreadable template vault " + vaultName + " for " + playerUUID, PersistenceQueue.cause(error));
                return false;
            }
            if(copy == null) return false;
            // While the copy was read the player may have left or opened something newer.
            if(!player.isConnected() || !SneakyVaults.getInstance().isLatestOpen(player, request)
                    || vault.isEditMode() || templateVaults.get(vaultName) != vault) {
                templateCopies.release(key);
                return false;
            }
            if(!showTemplate(player, copy)) {
                templateCopies.release(key);
                return false;
            }
            return true;
        });
    }

    /** @return False if another plugin cancelled the open. */
    private static boolean showTemplate(Player player, Inventory copy) {
        long shown = SneakyVaults.getInstance().probe.start();
        try {
            return player.openInventory(copy) != null;
        } finally {
            SneakyVaults.getInstance().probe.end("vault.template.open-inventory", shown);
        }
    }

    /**
     * Called when a player closes their copy of a template: queue writing it to their file.
     * @return Completes when written, or exceptionally if the write failed.
     */
    public CompletableFuture<Void> onTemplateCopyClosed(TemplateVault vault, Player player, Inventory closedInventory){
        String key = copyKey(player.getUniqueId().toString(), vault.getName());
        return templateCopies.save(key, copyWrite(key, closedInventory));
    }

    /** Server thread: snapshot a copy now and return the write of that snapshot. */
    private static PersistenceQueue.VoidTask copyWrite(String key, Inventory copy) {
        String[] parts = key.split("\0", 2);
        File file = PlayerVault.fileFor(parts[0]);
        String vaultName = parts[1];
        String inventory = InventoryUtility.inventoryToPaperYaml(copy.getContents());
        return () -> TemplateVault.writePlayerCopy(file, vaultName, inventory);
    }

    private static String copyKey(String playerUUID, String vaultName) {
        return playerUUID + "\0" + vaultName;
    }

    private static boolean isViewingTemplate(Player player, String vaultName) {
        return player.getOpenInventory().getTopInventory().getHolder() instanceof TemplateVault open
                && open.getName().equals(vaultName);
    }

    /**
     * Create a new edit session owned by the player for the vault name supplied
     * @param playerUUID Who owns the template edit session
     * @param vaultName The name of the vault that is being edited.
     * @return True if the session was created, false otherwise
     * */
    public boolean createEditSession(String playerUUID, String vaultName){
        FileConfiguration config = SneakyVaults.getInstance().getConfig();

        ConfigurationSection section = config.getConfigurationSection("template_vaults");
        if(section == null)
            section = config.createSection("template_vaults");

        ConfigurationSection vaultSection = section.getConfigurationSection(vaultName);
        if(vaultSection == null){
            return false;
        }
        Player player = Bukkit.getPlayer(UUID.fromString(playerUUID));
        if(player == null)
            return false;
        player.sendMessage(TextUtility.INSTANCE.convertToComponent("&aStarted new edit session for vault " + vaultName));
        int vaultSize = vaultSection.getInt("size", -1);
        if(vaultSize == -1){
            vaultSizeSession.put(playerUUID, vaultName);
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&eThere is no inventory for this vault!\n&6Please state the size of the vault (Must be a multiple of 9):"));
        }
        else {
            TemplateVault vault = getOrCreateTemplateVault(vaultName);
            if(vault == null){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cTemplate Vault does not exist!"));
                return false;
            }
            if(vault.isEditMode()){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cTemplate Vault is already currently being edited!"));
                return false;
            }

            for(Player otherPlayer : Bukkit.getOnlinePlayers()){
                Inventory openInv = otherPlayer.getOpenInventory().getTopInventory();
                if(openInv.getHolder() instanceof TemplateVault)
                    otherPlayer.closeInventory();
            }

            try {
                vault.startEditMode(player);
            } catch(IOException e){
                SneakyVaults.LOGGER.log(Level.SEVERE, "Template vault " + vaultName + " could not be read", e);
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cTemplate Vault could not be read! Its data was left unchanged."));
                return false;
            }
        }

        return true;
    }

    /**
     * Delete a template vault from the config. This cannot be undone!
     * @param vaultName The name of the template vault to remove
     * */
    public void deleteTemplateVault(String vaultName){
        FileConfiguration config = SneakyVaults.getInstance().getConfig();
        ConfigurationSection section = config.getConfigurationSection("template_vaults");
        if(section == null)
            section = config.createSection("template_vaults");

        // Close copies still open, so they are saved now and no stale holder outlives the template.
        for(Player player : Bukkit.getOnlinePlayers())
            if(isViewingTemplate(player, vaultName))
                player.closeInventory();

        section.set(vaultName, null);
        templateVaults.remove(vaultName);
        SneakyVaults.getInstance().saveConfig();
    }

    /**
     * Get currently existing vaults from the config
     * @return Set of strings containing all vault names
     * */
    public Set<String> getExistingTemplateVaults(){
        FileConfiguration config = SneakyVaults.getInstance().getConfig();

        ConfigurationSection section = config.getConfigurationSection("template_vaults");
        if(section == null)
            section = config.createSection("template_vaults");
        return section.getKeys(false);
    }

}
