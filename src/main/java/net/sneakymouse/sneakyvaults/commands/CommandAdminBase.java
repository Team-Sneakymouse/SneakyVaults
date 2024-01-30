package net.sneakymouse.sneakyvaults.commands;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public abstract class CommandAdminBase extends Command {

    public CommandAdminBase(@NotNull String name) {
        super(name);
        this.setPermission(SneakyVaults.IDENTIFIER + ".admin." + this.getName());
    }

    @Override
    public abstract boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args);

}
