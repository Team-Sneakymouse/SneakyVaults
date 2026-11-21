package net.sneakymouse.sneakyvaults.commands.player;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandBase;
import net.sneakymouse.sneakyvaults.integration.Probe;
import net.sneakymouse.sneakyvaults.integration.CharacterHook;
import net.sneakymouse.sneakyvaults.managers.SharedVaultManager;
import net.sneakymouse.sneakyvaults.persistence.PersistenceQueue;
import net.sneakymouse.sneakyvaults.types.SharedVault;
import com.sneakyutils.util.TextUtility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
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
        Probe probe = SneakyVaults.getInstance().probe;
        long opened = probe.start();
        try {
            return open(sender, args);
        } finally {
            probe.end("vault.character.open", opened);
        }
    }

    /** The command itself; timed as a whole by execute, its load and openInventory separately. */
    private boolean open(@NotNull CommandSender sender, @NotNull String[] args) {
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

        SharedVaultManager manager = SneakyVaults.getInstance().sharedVaultManager;
        int number = vaultNumber;
        long loading = SneakyVaults.getInstance().probe.start();
        CompletableFuture<SharedVault> load;
        try {
            load = manager.getCharacterVault(player, character.uuid(), character.name(), vaultNumber);
        } finally {
            SneakyVaults.getInstance().probe.end("vault.character.load", loading);
        }

        load.whenComplete((vault, error) -> {
            // Runs on the server thread, now or once the vault has been read.
            if(error != null) {
                SneakyVaults.LOGGER.log(Level.SEVERE, "Refusing to open unreadable character vault " + number + " for " + character.uuid(), PersistenceQueue.cause(error));
                if(player.isOnline())
                    player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThat vault could not be read safely. A server maintainer has been notified."));
                return;
            }
            if(vault == null) {
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cYou don't have access to character vault " + number + "!"));
                return;
            }
            // The player may have left or switched character while the vault was read.
            CharacterHook.CharacterRef current = player.isOnline() ? CharacterHook.current(player) : null;
            if(current == null || !current.uuid().equals(character.uuid())) {
                manager.release(vault);
                return;
            }

            long shown = SneakyVaults.getInstance().probe.start();
            try {
                player.openInventory(vault.getInventory());
            } finally {
                SneakyVaults.getInstance().probe.end("vault.character.open-inventory", shown);
            }
        });
        return false;
    }
}
