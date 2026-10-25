package net.sneakymouse.sneakyvaults.commands.player;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandBase;
import net.sneakymouse.sneakyvaults.integration.CharacterHook;
import net.sneakymouse.sneakyvaults.types.SharedVault;
import com.sneakyutils.util.TextUtility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

public class CommandCharacterVault extends CommandBase {

    public CommandCharacterVault() {
        super("cvault");
        this.setAliases(List.of("charactervault", "cv", "charvault"));
        this.description = "Open your current character's vault!";
        this.usageMessage = "/" + this.getName() + " [Vault Number]";
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if(!(sender instanceof Player player)) return false;

        if(!SneakyVaults.getInstance().hasCharacters()) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cCharacter vaults are not available on this server."));
            return false;
        }

        if(args.length > 1) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cInvalid Usage: " + this.usageMessage));
            return false;
        }

        int vaultNumber = 1;
        if(args.length == 1) {
            try {
                vaultNumber = Integer.parseInt(args[0]);
            } catch(NumberFormatException e) {
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cInvalid Number! " + this.usageMessage));
                return false;
            }
        }

        CharacterHook.CharacterRef character = CharacterHook.current(player);
        if(character == null) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cYou need to be playing a character to open a character vault."));
            return false;
        }

        SharedVault vault;
        try {
            vault = SneakyVaults.getInstance().sharedVaultManager.getCharacterVault(player, character.uuid(), character.name(), vaultNumber);
        } catch(IOException e) {
            SneakyVaults.LOGGER.log(Level.SEVERE, "Refusing to open unreadable character vault " + vaultNumber + " for " + character.uuid(), e);
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThat vault could not be read safely. A server maintainer has been notified."));
            return false;
        }

        if(vault == null) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cYou don't have access to character vault " + vaultNumber + "!"));
            return false;
        }

        player.openInventory(vault.getInventory());
        return false;
    }
}
