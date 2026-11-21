package net.sneakymouse.sneakyvaults.commands.admin;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandAdminBase;
import net.sneakymouse.sneakyvaults.wall.VaultWall;
import net.sneakymouse.sneakyvaults.wall.WallChest;
import com.sneakyutils.util.TextUtility;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** {@code /vaultwall reload|list|grant|reset}: manage the bank's vault wall (see vault-wall.yml). */
public class CommandVaultWall extends CommandAdminBase {

    private static final List<String> SUBCOMMANDS = List.of("reload", "list", "grant", "reset");

    public CommandVaultWall() {
        super("vaultwall");
        this.description = "Manage the bank's vault wall";
        this.usageMessage = "/vaultwall reload | list [player] | grant <player> <chest> | reset <player> <chest|all>";
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if(!testPermission(sender)) return false;
        VaultWall wall = SneakyVaults.getInstance().vaultWall;
        if(args.length == 0) {
            send(sender, "&cUsage: " + usageMessage);
            return false;
        }
        switch(args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> wall.reload().thenAccept(problems -> {
                if(problems.isEmpty()) send(sender, "&aReloaded " + VaultWall.FILE + ": " + wall.config().chests().size()
                        + " chests, " + (wall.config().enabled() ? "enabled" : "disabled") + ".");
                else {
                    send(sender, "&eReloaded " + VaultWall.FILE + " with problems (also in the console):");
                    for(String problem : problems) send(sender, "&7- " + problem);
                }
            });
            case "list" -> list(sender, wall, args);
            case "grant", "reset" -> setOwned(sender, wall, args, args[0].equalsIgnoreCase("grant"));
            default -> send(sender, "&cUsage: " + usageMessage);
        }
        return true;
    }

    private void list(CommandSender sender, VaultWall wall, String[] args) {
        Player target = null;
        if(args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if(target == null) {
                send(sender, "&cThat player is not online.");
                return;
            }
        }
        send(sender, "&6Vault wall &7(" + (wall.config().enabled() ? "&aenabled" : "&cdisabled")
                + (wall.hasVariables() ? "" : "&7, &cno SneakyVariables") + "&7):");
        for(WallChest chest : wall.config().chests().values()) {
            StringBuilder line = new StringBuilder("&e" + chest.id() + " &7vault " + chest.vault() + " at "
                    + chest.world() + " " + chest.x() + " " + chest.y() + " " + chest.z());
            if(chest.price() != null) line.append(" &7price ").append(chest.price().amount()).append(' ').append(chest.price().currency());
            if(chest.requires() != null) line.append(" &7needs ").append(chest.requires().variable()).append(" >= ").append(chest.requires().min());
            if(chest.existingOnly()) line.append(" &7existing only");
            if(target != null && chest.price() != null) {
                line.append(wall.isOwned(target, chest) ? " &aowned" : " &cnot owned")
                        .append(" &7(pays ").append(wall.priceFor(target, chest)).append(')');
            }
            send(sender, line.toString());
        }
    }

    private void setOwned(CommandSender sender, VaultWall wall, String[] args, boolean owned) {
        if(args.length != 3) {
            send(sender, "&cUsage: " + usageMessage);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if(target == null) {
            send(sender, "&cThat player is not online.");
            return;
        }
        if(!wall.isLoaded(target)) {
            send(sender, "&cThat player's variables are not loaded on this server yet.");
            return;
        }
        Collection<WallChest> chests;
        if(!owned && args[2].equalsIgnoreCase("all")) {
            chests = wall.config().chests().values();
        } else {
            WallChest chest = wall.config().chests().get(args[2]);
            if(chest == null) {
                send(sender, "&cNo chest called " + args[2] + ". See /vaultwall list.");
                return;
            }
            chests = List.of(chest);
        }
        int changed = 0;
        for(WallChest chest : chests)
            if(wall.setOwned(target, chest, owned)) changed++;
        if(changed == 0) send(sender, "&cThat chest can't be bought, so there is nothing to " + (owned ? "grant" : "reset") + ".");
        else send(sender, "&a" + (owned ? "Granted " : "Reset ") + changed + " vault wall chest(s) for " + target.getName() + ".");
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        if(args.length == 1) options.addAll(SUBCOMMANDS);
        else if(args.length == 2 && !args[0].equalsIgnoreCase("reload"))
            for(Player player : Bukkit.getOnlinePlayers()) options.add(player.getName());
        else if(args.length == 3 && (args[0].equalsIgnoreCase("grant") || args[0].equalsIgnoreCase("reset"))) {
            for(WallChest chest : SneakyVaults.getInstance().vaultWall.config().chests().values())
                if(chest.ownedVariable() != null) options.add(chest.id());
            if(args[0].equalsIgnoreCase("reset")) options.add("all");
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(TextUtility.INSTANCE.convertToComponent(message));
    }
}
