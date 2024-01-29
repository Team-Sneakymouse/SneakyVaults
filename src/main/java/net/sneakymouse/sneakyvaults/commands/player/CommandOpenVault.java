package net.sneakymouse.sneakyvaults.commands.player;

import net.sneakymouse.sneakyvaults.commands.CommandBase;
import net.sneakymouse.sneakyvaults.utlitiy.ChatUtility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class CommandOpenVault extends CommandBase {
    protected CommandOpenVault() {
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
        }
        else if(args.length == 1){
            //ToDo: Load Vault based on supplied number!
        }
        else {
            player.sendMessage(ChatUtility.convertToComponent("&cInvalid Usage: " + this.usageMessage));
        }


        return false;
    }
}
