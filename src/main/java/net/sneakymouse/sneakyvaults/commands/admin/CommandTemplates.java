package net.sneakymouse.sneakyvaults.commands.admin;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandAdminBase;
import com.sneakyutils.util.TextUtility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class CommandTemplates extends CommandAdminBase {


    public CommandTemplates() {
        super("templates");
        this.description = "Handle all data relating to template vaults";
        this.usageMessage = "/templates [create/edit/delete] [vault name]";
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {

        if(!(sender instanceof Player player)) return false;

        if(args.length == 0){
            StringBuilder resultMessage = new StringBuilder();
            resultMessage.append("&eCurrently existing vaults:\n");
            for(String name : SneakyVaults.getInstance().vaultManager.getExistingTemplateVaults()){
                resultMessage.append("&b- ").append(name).append("\n");
            }
            player.sendMessage(TextUtility.INSTANCE.convertToComponent(resultMessage.toString()));
            return false;
        }

        if(args.length != 2){
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&4Invalid Usage: " + this.usageMessage));
            return false;
        }

        if(args[0].equalsIgnoreCase("create")){
            String vaultName = args[1];

            if(!SneakyVaults.getInstance().vaultManager.createTemplateVault(vaultName)){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cFailed to create vault " + vaultName + " as it already exists!"));
                return false;
            }

            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&eCreated new vault " + vaultName));
        }
        else if(args[0].equalsIgnoreCase("edit")) {
            String vaultName = args[1];

            if(!SneakyVaults.getInstance().vaultManager.createEditSession(player.getUniqueId().toString(), vaultName)){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&4You must create a vault before you can edit a vault!"));
                return false;
            }
        }
        else if(args[0].equalsIgnoreCase("delete")){
            String vaultName = args[1];
            SneakyVaults.getInstance().vaultManager.deleteTemplateVault(vaultName);
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&eDeleted vault " + vaultName + " if it exists!"));
        }

        return false;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) throws IllegalArgumentException {
        if(args.length == 1){
            return List.of("create", "delete", "edit");
        }
        else if(args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("edit"))){
            return new ArrayList<>(SneakyVaults.getInstance().vaultManager.getExistingTemplateVaults());
        }

        return super.tabComplete(sender, alias, args);
    }
}
