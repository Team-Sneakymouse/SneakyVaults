package net.sneakymouse.sneakyvaults.commands.admin;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandAdminBase;
import net.sneakymouse.sneakyvaults.integration.CharacterHook;
import net.sneakymouse.sneakyvaults.types.SharedVault;
import com.sneakyutils.util.TextUtility;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class CommandPeekCharacterVault extends CommandAdminBase {

    public CommandPeekCharacterVault() {
        super("peekcvault");
        this.setAliases(List.of("peekcharactervault", "peekcv"));
        this.description = "Look into a character's vault";
        this.usageMessage = "/peekcvault <online player|character uuid> [vault number]";
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if(!(sender instanceof Player player)) return false;

        if(!SneakyVaults.getInstance().hasCharacters()) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cCharacter vaults need SneakyCharacterManager."));
            return false;
        }

        if(args.length < 1 || args.length > 2) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&4Invalid Usage: " + this.usageMessage));
            return false;
        }

        int vaultNumber = 1;
        if(args.length == 2) {
            try {
                vaultNumber = Integer.parseInt(args[1]);
            } catch(NumberFormatException e) {
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&4Invalid Vault Number! " + this.usageMessage));
                return false;
            }
        }

        // A UUID is a character UUID; a name means that online player's current character.
        String characterUUID;
        try {
            characterUUID = UUID.fromString(args[0]).toString();
        } catch(IllegalArgumentException e) {
            Player target = Bukkit.getPlayer(args[0]);
            CharacterHook.CharacterRef character = target == null ? null : CharacterHook.current(target);
            if(character == null) {
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThat player is not online playing a character. Use the character UUID instead."));
                return false;
            }
            characterUUID = character.uuid();
        }

        SharedVault vault;
        try {
            vault = SneakyVaults.getInstance().sharedVaultManager.getExistingCharacterVault(characterUUID, vaultNumber);
        } catch(IOException e) {
            SneakyVaults.LOGGER.log(Level.SEVERE, "Refusing to open unreadable character vault " + vaultNumber + " for " + characterUUID, e);
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThat vault could not be read safely. Its file was left unchanged."));
            return false;
        }

        if(vault == null) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThat vault does not exist."));
            return false;
        }

        player.openInventory(vault.getInventory());
        return false;
    }
}
