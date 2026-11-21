package net.sneakymouse.sneakyvaults.commands.admin;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandAdminBase;
import net.sneakymouse.sneakyvaults.integration.CharacterHook;
import net.sneakymouse.sneakyvaults.managers.SharedVaultManager;
import net.sneakymouse.sneakyvaults.persistence.PersistenceQueue;
import com.sneakyutils.util.TextUtility;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

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

        SharedVaultManager manager = SneakyVaults.getInstance().sharedVaultManager;
        String character = characterUUID;
        int number = vaultNumber;
        long request = SneakyVaults.getInstance().beginOpen(player);
        manager.getExistingCharacterVault(characterUUID, vaultNumber).whenComplete((vault, error) -> {
            // Runs on the server thread, now or once the vault has been read.
            if(error != null) {
                SneakyVaults.LOGGER.log(Level.SEVERE, "Refusing to open unreadable character vault " + number + " for " + character, PersistenceQueue.cause(error));
                if(player.isConnected())
                    player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThat vault could not be read safely. Its file was left unchanged."));
                return;
            }
            if(vault == null) {
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThat vault does not exist."));
                return;
            }
            // The admin may have left, opened something newer, or lost the command permission meanwhile.
            if(!player.isConnected() || !SneakyVaults.getInstance().isLatestOpen(player, request) || !testPermissionSilent(player)) {
                manager.release(vault);
                return;
            }
            if(player.openInventory(vault.getInventory()) == null)
                manager.release(vault);
        });
        return false;
    }
}
