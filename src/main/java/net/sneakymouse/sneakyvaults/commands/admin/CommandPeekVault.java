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

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

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

        if(args.length < 1 || args.length > 2){
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

        if(vaultNumber < 1) {
            player.sendMessage(ChatUtility.convertToComponent("&4Vault number must be 1 or higher."));
            return false;
        }

        UUID targetUUID;
        try {
            targetUUID = UUID.fromString(args[0]);
        } catch(IllegalArgumentException exception) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayerIfCached(args[0]);
            if(offlinePlayer == null) {
                player.sendMessage(ChatUtility.convertToComponent(
                        "&cThat player is not in Bukkit's cache. Use their UUID instead."
                ));
                return false;
            }
            targetUUID = offlinePlayer.getUniqueId();
        }

        PlayerVault vault;
        try {
            vault = SneakyVaults.getInstance().vaultManager.getExistingPlayerVault(
                    targetUUID.toString(),
                    vaultNumber
            );
        } catch(IOException exception) {
            SneakyVaults.LOGGER.log(
                    Level.SEVERE,
                    "Refusing to open corrupt vault " + vaultNumber + " for " + targetUUID,
                    exception
            );
            player.sendMessage(ChatUtility.convertToComponent(
                    "&cThat vault could not be read safely. Its file was left unchanged."
            ));
            return false;
        }

        if(vault == null) {
            player.sendMessage(ChatUtility.convertToComponent("&cThat vault does not exist."));
            return false;
        }

        if(vault.isOpened) {
            player.sendMessage(ChatUtility.convertToComponent("&cThat vault is already open."));
            return false;
        }

        vault.isOpened = true;
        try {
            if(player.openInventory(vault.getInventory(false)) == null) {
                vault.isOpened = false;
                player.sendMessage(ChatUtility.convertToComponent("&cAnother plugin prevented that vault from opening."));
            }
        } catch(RuntimeException exception) {
            vault.isOpened = false;
            throw exception;
        }
        return false;
    }
}
