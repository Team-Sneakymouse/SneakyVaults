package net.sneakymouse.sneakyvaults.commands.admin;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandAdminBase;
import net.sneakymouse.sneakyvaults.utlitiy.ChatUtility;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class CommandOpenTemplate extends CommandAdminBase {


    public CommandOpenTemplate() {
        super("opentemplate");
        this.description = "Open template vault for a player";
        this.usageMessage = "/opentemplate <Template Vault> <Player> [true/false - To Force New Template]";
        this.setAliases(List.of("otemplate", "opentemp", "otemp"));
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {

        if(args.length < 2){
            sender.sendMessage(ChatUtility.convertToComponent("&cInvalid Usage: " + this.usageMessage));
            return false;
        }

        String vaultName = args[0];
        String playerName = args[1];

        boolean toForce = true;
        if(args.length == 3)
            toForce = Boolean.parseBoolean(args[2]);

        Player target = Bukkit.getPlayer(playerName);
        if(target == null){
            sender.sendMessage(ChatUtility.convertToComponent("&cInvalid target player! " + this.usageMessage));
            return false;
        }

        if(SneakyVaults.getInstance().vaultManager.openTemplateVault(target.getUniqueId().toString(), vaultName, toForce)){
            sender.sendMessage(ChatUtility.convertToComponent("&eOpened vault for player!"));
            target.sendMessage(ChatUtility.convertToComponent("&aOpened vault!"));
        }
        else {
            sender.sendMessage(ChatUtility.convertToComponent("&cFailed to open template vault!"));
        }

        return false;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) throws IllegalArgumentException {
        if(args.length == 1){
            return new ArrayList<>(SneakyVaults.getInstance().vaultManager.getExistingTemplateVaults());
        }
        else if(args.length == 2){
            return super.tabComplete(sender, alias, args);
        }
        else if(args.length == 3){
            return List.of("true", "false");
        }
        return super.tabComplete(sender, alias, args);
    }
}
