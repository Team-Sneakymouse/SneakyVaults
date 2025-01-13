package net.sneakymouse.sneakyvaults.commands.admin;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandAdminBase;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import net.sneakymouse.sneakyvaults.utlitiy.ChatUtility;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class CommandPeekVault extends CommandAdminBase {


    public CommandPeekVault() {
        super("peekvault");
        this.description = "Take a look into a players vault";
        this.usageMessage = "/peekvault <Player> [vault number]";
        this.setAliases(List.of("peek", "peekv", "vaultsee", "vsee"));
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if(!(sender instanceof Player player)) return false;

        if(args.length == 0){
            sender.sendMessage(ChatUtility.convertToComponent("&4Invalid Usage: " + this.usageMessage));
            return false;
        }

        int vaultNumber = 1;
        if(args.length == 2){
            try {
                vaultNumber = Integer.parseInt(args[1]);
            } catch(NumberFormatException e){
                player.sendMessage(ChatUtility.convertToComponent("&4Invalid Vault Number! " + this.usageMessage));
                return false;
            }
        }


        String playerName = args[0];
        String targetUUID;
        if(playerName.length() > 20){ //Should be a UUID and I'm lazy to actually verify this with regex
            targetUUID = playerName;
        }
        else {
            Player target = Bukkit.getPlayer(playerName);

            if(target == null){
                player.sendMessage(ChatUtility.convertToComponent("&ePlayer Offline.. Attempting to load offline player vault!"));
                    int finalVaultNumber = vaultNumber;
                    Bukkit.getAsyncScheduler().runNow(SneakyVaults.getInstance(), (_s) ->{
                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);

                    String uuid = offlinePlayer.getUniqueId().toString();
                    PlayerVault vault = SneakyVaults.getInstance().vaultManager.peekPlayerVault(uuid, finalVaultNumber);

                    if(vault == null){
                        player.sendMessage(ChatUtility.convertToComponent("&4Error. Could not get vault. The player cannot have that many vaults || The player has the vault opened."));
                        return;
                    }


                    Bukkit.getScheduler().runTask(SneakyVaults.getInstance(), () -> {
                        player.openInventory(vault.getInventory(false));
                        vault.isOpened = true;
                    });
                });
                return false;
            }

            targetUUID = target.getUniqueId().toString();
        }


        PlayerVault vault = SneakyVaults.getInstance().vaultManager.peekPlayerVault(targetUUID, vaultNumber);


        if(vault == null){
            player.sendMessage(ChatUtility.convertToComponent("&4Error. Could not get vault. The player cannot have that many vaults || The player has the vault opened."));
            return false;
        }


        player.openInventory(vault.getInventory(false));
        vault.isOpened = true;
        return false;
    }
}
