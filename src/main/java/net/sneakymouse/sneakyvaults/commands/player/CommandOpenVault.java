package net.sneakymouse.sneakyvaults.commands.player;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandBase;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import com.sneakyutils.util.TextUtility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class CommandOpenVault extends CommandBase {
    public CommandOpenVault() {
        super("openvault");
        this.setAliases(List.of("ov", "pv", "openv", "ovault", "vault"));
        this.description = "Open your personal vault!";
        this.usageMessage = "/" + this.getName() + " [Vault Number]";
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {

        if(!(sender instanceof Player player)) return false;

        if(args.length == 0){
            //ToDo: Load Vault #1 (if it exists)
            PlayerVault vault = SneakyVaults.getInstance().vaultManager.getPlayerVault(player.getUniqueId().toString(), 1);
            if(vault == null){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cCould not find your player vault!"));
                return false;
            }
            if(vault.isOpened){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&4That vault is currently opened.. Try again later."));
                return false;
            }
            player.openInventory(vault.getInventory());
            vault.isOpened = true;
        }
        else if(args.length == 1){
            //ToDo: Load Vault based on supplied number!
            try {
                int vaultNumber = Integer.parseInt(args[0]);
                if(vaultNumber <= 0){
                    player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cVault number must be 1 or higher!"));
                    return false;
                }
                PlayerVault vault = SneakyVaults.getInstance().vaultManager.getPlayerVault(player.getUniqueId().toString(), vaultNumber);
                if(vault == null){
                    player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cCould not find your player vault!"));
                    return false;
                }
                if(vault.isOpened){
                    player.sendMessage(TextUtility.INSTANCE.convertToComponent("&4That vault is currently opened.. Try again later."));
                    return false;
                }
                player.openInventory(vault.getInventory());
                vault.isOpened = true;
            } catch(NumberFormatException e){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cInvalid Number! " + this.usageMessage));
            }
        }
        else {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cInvalid Usage: " + this.usageMessage));
        }
        return false;
    }
}
