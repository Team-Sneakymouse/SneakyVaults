package net.sneakymouse.sneakyvaults.managers;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.sneakymouse.sneakyvaults.SneakyVaults.IDENTIFIER;

public class VaultManager {

    public Map<String, Map<Integer, PlayerVault>> playerVaults;

    public VaultManager(){
        playerVaults = new HashMap<>();
    }

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

    public int getLastVault(String playerUUID){
        Map<Integer, PlayerVault> vaults = playerVaults.getOrDefault(playerUUID, new HashMap<>());
        return vaults.size();
    }

    public int getMaxAllowedVaults(String playerUUID){
        Player player = Bukkit.getPlayer(UUID.fromString(playerUUID));
        if(player == null) return -1;

        if(player.hasPermission(IDENTIFIER + ".max.tier3")){
            return SneakyVaults.getInstance().getConfig().getInt("max_vault_permissions.tier3");
        } else if(player.hasPermission(IDENTIFIER + ".max.tier2")){
            return SneakyVaults.getInstance().getConfig().getInt("max_vault_permissions.tier2");
        }
        if(player.hasPermission(IDENTIFIER + ".max.tier1")){
            return SneakyVaults.getInstance().getConfig().getInt("max_vault_permissions.tier1");
        }
        if(player.hasPermission(IDENTIFIER + ".max.tier0")){
            return SneakyVaults.getInstance().getConfig().getInt("max_vault_permissions.tier0");
        }
        return 0;
    }

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

    public @Nullable PlayerVault getPlayerVault(@NotNull String playerUUID, int vaultNumber){
        if(vaultNumber > getMaxAllowedVaults(playerUUID) || vaultNumber < 0) return null;

        if(!playerVaults.containsKey(playerUUID)) {
            Map<Integer, PlayerVault> vaults = new HashMap<>();
            if(getMaxAllowedVaults(playerUUID) > getLastVault(playerUUID)){
                if(getMaxVaultSize(playerUUID) >= 9){
                    PlayerVault vault = createVault(playerUUID, getMaxVaultSize(playerUUID));
                    if(vault == null) return null;
                    vaults.put(getLastVault(playerUUID), vault);
                    playerVaults.put(playerUUID, vaults);
                    return vault;
                }
            }
            return null;
        }
        Map<Integer, PlayerVault> vaults = playerVaults.get(playerUUID);
        PlayerVault vault = vaults.get(vaultNumber);
        if(vault == null){
            if(getMaxAllowedVaults(playerUUID) > getLastVault(playerUUID)){
                if(getMaxVaultSize(playerUUID) >= 9){
                    vault = createVault(playerUUID, getMaxVaultSize(playerUUID));
                    if(vault == null) return null;
                    vaults.put(getLastVault(playerUUID), vault);
                    return vault;
                }
            }
        }
        return vault;
    }

    private @Nullable PlayerVault createVault(@NotNull String playerUUID, int size){
        Map<Integer, PlayerVault> vaults;
        if(!playerVaults.containsKey(playerUUID))
            vaults = new HashMap<>();
        else
            vaults = playerVaults.get(playerUUID);

        try{
            PlayerVault vault = new PlayerVault(playerUUID, size, getLastVault(playerUUID) + 1, false);
            vaults.put(getLastVault(playerUUID) + 1, vault);
            playerVaults.put(playerUUID, vaults);
            return vault;
        } catch(IOException e){
            SneakyVaults.LOGGER.severe("Could not create player vault!");
        }
        return null;
    }

}
