package net.sneakymouse.sneakyvaults.commands.admin;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandAdminBase;
import net.sneakymouse.sneakyvaults.persistence.PersistenceQueue;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import com.sneakyutils.util.TextUtility;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

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
            sender.sendMessage(TextUtility.INSTANCE.convertToComponent("&4Invalid Usage: " + this.usageMessage));
            return false;
        }

        int vaultNumber = 1;
        if(args.length == 2){
            try {
                vaultNumber = Integer.parseInt(args[1]);
            } catch(NumberFormatException e){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&4Invalid Vault Number! " + this.usageMessage));
                return false;
            }
        }

        if(vaultNumber < 1) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&4Vault number must be 1 or higher."));
            return false;
        }

        UUID targetUUID;
        try {
            targetUUID = UUID.fromString(args[0]);
        } catch(IllegalArgumentException exception) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayerIfCached(args[0]);
            if(offlinePlayer == null) {
                player.sendMessage(TextUtility.INSTANCE.convertToComponent(
                        "&cThat player is not in Bukkit's cache. Use their UUID instead."
                ));
                return false;
            }
            targetUUID = offlinePlayer.getUniqueId();
        }

        UUID target = targetUUID;
        int number = vaultNumber;
        SneakyVaults.getInstance().vaultManager.getExistingPlayerVault(target.toString(), vaultNumber).whenComplete((vault, error) -> {
            // Runs on the server thread, now or once the vault has been read.
            if(!player.isOnline()) return;
            if(error != null) {
                SneakyVaults.LOGGER.log(
                        Level.SEVERE,
                        "Refusing to open corrupt vault " + number + " for " + target,
                        PersistenceQueue.cause(error)
                );
                player.sendMessage(TextUtility.INSTANCE.convertToComponent(
                        "&cThat vault could not be read safely. Its file was left unchanged."
                ));
                return;
            }
            open(player, vault);
        });
        return false;
    }

    private static void open(Player player, PlayerVault vault) {
        if(vault == null) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThat vault does not exist."));
            return;
        }

        if(vault.isOpened) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThat vault is already open."));
            return;
        }

        vault.isOpened = true;
        try {
            if(player.openInventory(vault.getInventory()) == null) {
                vault.isOpened = false;
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cAnother plugin prevented that vault from opening."));
            }
        } catch(RuntimeException exception) {
            vault.isOpened = false;
            throw exception;
        }
    }
}
