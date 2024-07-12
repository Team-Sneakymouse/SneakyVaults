package net.sneakymouse.sneakyvaults.managers;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import net.sneakymouse.sneakyvaults.types.TemplateVault;
import net.sneakymouse.sneakyvaults.utlitiy.ChatUtility;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.sneakymouse.sneakyvaults.SneakyVaults.IDENTIFIER;

public class VaultManager {

    public Map<String, Map<Integer, PlayerVault>> playerVaults;
    public Map<String, String> vaultSizeSession;

    public Map<String, TemplateVault> templateVaults;

    public VaultManager(){
        playerVaults = new HashMap<>();
        vaultSizeSession = new HashMap<>();
        templateVaults = new HashMap<>();
    }

    /**
     * Save all currently active vaults
     * */
    public void saveAllVaults(){
        for(Map<Integer, PlayerVault> vaults : playerVaults.values()){
            vaults.forEach((num, vault) ->{
                try{
                    vault.saveVault();
                } catch (IOException e){
                    SneakyVaults.LOGGER.severe("Bad: Failed to save a players vault!");
                }
            });
        }
    }

    /**
     * Gets the number of vaults a player currently has.
     * @param playerUUID UUID of the player to check vaults of
     * @return The size of the player vault count
     * */
    /*
    public int getLastVault(String playerUUID){
        Map<Integer, PlayerVault> vaults = playerVaults.getOrDefault(playerUUID, new HashMap<>());
        return vaults.size();
    }*/

    /**
     * Get the max number of allowed vaults a player has permission to own.
     * @param playerUUID UUID of the player to check max vaults
     * @return Maximum number of vaults a player has permission to own.
     * */
    public int getMaxAllowedVaults(String playerUUID){
        Player player = Bukkit.getPlayer(UUID.fromString(playerUUID));
        if(player == null) return -1;
        return player.getEffectivePermissions().stream().filter(p -> p.getPermission().startsWith(IDENTIFIER + ".max.")).map(p -> {
            var split = p.getPermission().split("\\.");
            return Integer.parseInt(split[split.length - 1]);
        }).max(Integer::compare).orElse(0);
    }

    /**
     * Gets the maximum vault size a player has permission to have
     * @param playerUUID UUID of the player to check the max vault size of.
     * @return max number of slots for the vault. Will be a multiple of 9
     * */
    public int getMaxVaultSize(String playerUUID){
        Player player = Bukkit.getPlayer(UUID.fromString(playerUUID));
        if(player == null) return -1;
        for(int i = 6; i >= 0; i--){
            if(player.hasPermission(IDENTIFIER +".slots." + 9*i)){
                return 9*i;
            }
        }
        return -1;
    }

    /**
     * Peek another players vault. Technically not required other than checking if the vault is opened already
     * @param playerUUID UUID of the player to get the vault of
     * @param vaultNumber Number of the vault to get.
     * @return PlayerVault object or null if no vault exist or a vault is opened.
     * @see PlayerVault
     * */
    public @Nullable PlayerVault peekPlayerVault(@NotNull String playerUUID, int vaultNumber){
        PlayerVault vault = getPlayerVault(playerUUID, vaultNumber);

        //Does the player have a vault (technically creates one if allowed)
        //Is the vault opened?
        if(vault == null || vault.isOpened) return null;
        return vault;
    }

    /**
     * Get a players player vault based off UUID and Vault Number.
     * This will create a new vault if one does not exist already.
     * @param playerUUID UUID of the player to get the vault of
     * @param vaultNumber The vault number to get.
     * @return PlayerVault object or null if the vault doesn't exist and the player cannot create a new vault
     * @see PlayerVault
     * */
    public @Nullable PlayerVault getPlayerVault(@NotNull String playerUUID, int vaultNumber){
        if(vaultNumber > getMaxAllowedVaults(playerUUID) || vaultNumber < 0) return null;

        if(!playerVaults.containsKey(playerUUID)) {
            Map<Integer, PlayerVault> vaults = new HashMap<>();
            if(getMaxAllowedVaults(playerUUID) > vaultNumber){
                if(getMaxVaultSize(playerUUID) >= 9){
                    PlayerVault vault = createVault(playerUUID, getMaxVaultSize(playerUUID), vaultNumber);
                    if(vault == null) return null;
                    vaults.put(vaultNumber, vault);
                    playerVaults.put(playerUUID, vaults);
                    return vault;
                }
            }
            return null;
        }
        Map<Integer, PlayerVault> vaults = playerVaults.get(playerUUID);
        PlayerVault vault = vaults.get(vaultNumber);
        if(vault == null){
            if(getMaxAllowedVaults(playerUUID) > vaultNumber){
                if(getMaxVaultSize(playerUUID) >= 9){
                    vault = createVault(playerUUID, getMaxVaultSize(playerUUID), vaultNumber);
                    if(vault == null) return null;
                    vaults.put(vaultNumber, vault);
                    return vault;
                }
            }
        }
        return vault;
    }

    /**
     * Create a new playervault for the UUID supplied of stated size.
     * @param playerUUID UUID of the player to create a vault for
     * @param size Size of the vault to create. Note: MUST be a Multiple of 9.
     * @return PlayerVault object or null if vault creation fails.
     * @see PlayerVault
     * */
    private @Nullable PlayerVault createVault(@NotNull String playerUUID, int size, int vaultNumber){
        Map<Integer, PlayerVault> vaults;
        if(!playerVaults.containsKey(playerUUID))
            vaults = new HashMap<>();
        else
            vaults = playerVaults.get(playerUUID);

        try{
            PlayerVault vault = new PlayerVault(playerUUID, size, vaultNumber, false);
            vaults.put(vaultNumber, vault);
            playerVaults.put(playerUUID, vaults);
            return vault;
        } catch(IOException e){
            SneakyVaults.LOGGER.severe("Could not create player vault!");
        }
        return null;
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
            section = config.createSection("tempalte_vaults");

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

        return new TemplateVault(vaultName, vaultSize, false);
    }

    /**
     * Opens the template inventory for the player.
     * @param playerUUID The player UUID to open the inventory for
     * @param vaultName The name of the template vault to open
     * @param toForce True if you want it to create a new vault inventory each time. False will open the last vault the player opened
     * @return True if the vault was opened, False otherwise
     * */
    public boolean openTemplateVault(String playerUUID, String vaultName, boolean toForce){
        TemplateVault vault = getOrCreateTemplateVault(vaultName);
        if(vault == null) return false;
        if(vault.isEditMode()) return false;

        Inventory inventory = vault.getInventory(playerUUID, toForce);
        Player player = Bukkit.getPlayer(UUID.fromString(playerUUID));
        if(player == null) return false;

        player.openInventory(inventory);
        return true;
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
        player.sendMessage(ChatUtility.convertToComponent("&aStarted new edit session for vault " + vaultName));
        int vaultSize = vaultSection.getInt("size", -1);
        if(vaultSize == -1){
            vaultSizeSession.put(playerUUID, vaultName);
            player.sendMessage(ChatUtility.convertToComponent("&eThere is no inventory for this vault!\n&6Please state the size of the vault (Must be a multiple of 9):"));
        }
        else {
            TemplateVault vault = getOrCreateTemplateVault(vaultName);
            if(vault == null){
                player.sendMessage(ChatUtility.convertToComponent("&cTemplate Vault does not exist!"));
                return false;
            }
            if(vault.isEditMode()){
                player.sendMessage(ChatUtility.convertToComponent("&cTemplate Vault is already currently being edited!"));
                return false;
            }

            for(Player otherPlayer : Bukkit.getOnlinePlayers()){
                Inventory openInv = otherPlayer.getOpenInventory().getTopInventory();
                if(openInv.getHolder() instanceof TemplateVault)
                    otherPlayer.closeInventory();
            }

            vault.startEditMode(player);

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

        section.set(vaultName, null);
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
