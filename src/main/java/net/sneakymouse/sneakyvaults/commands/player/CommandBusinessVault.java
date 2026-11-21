package net.sneakymouse.sneakyvaults.commands.player;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandBase;
import net.sneakymouse.sneakyvaults.integration.Probe;
import net.sneakymouse.sneakyvaults.integration.CharacterHook;
import net.sneakymouse.sneakyvaults.managers.SharedVaultManager;
import net.sneakymouse.sneakyvaults.persistence.PersistenceQueue;
import net.sneakymouse.sneakyvaults.types.Business;
import net.sneakymouse.sneakyvaults.types.SharedVault;
import com.sneakyutils.util.TextUtility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class CommandBusinessVault extends CommandBase {

    public CommandBusinessVault() {
        super("bvault");
        this.setAliases(List.of("businessvault", "bv"));
        this.description = "Open a vault shared with your business!";
        this.usageMessage = "/" + this.getName() + " [Business] [Vault Number]";
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        Probe probe = SneakyVaults.getInstance().probe;
        long opened = probe.start();
        try {
            return open(sender, args);
        } finally {
            probe.end("vault.business.open", opened);
        }
    }

    /** The command itself; timed as a whole by execute, its load and openInventory separately. */
    private boolean open(@NotNull CommandSender sender, @NotNull String[] args) {
        if(!(sender instanceof Player player)) return false;

        if(!SneakyVaults.getInstance().hasCharacters()) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cBusiness vaults are not available on this server."));
            return false;
        }

        CharacterHook.CharacterRef character = CharacterHook.current(player);
        if(character == null) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cYou need to be playing a character to open a business vault."));
            return false;
        }

        List<Business> businesses = SneakyVaults.getInstance().sharedVaultManager.getBusinessesOf(character.uuid());
        if(businesses.isEmpty()) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&c" + character.name() + " is not part of any business."));
            return false;
        }

        // The business can be left out when the character belongs to exactly one.
        Business business;
        String numberArg = null;
        if(args.length == 0 || (args.length == 1 && isNumber(args[0]))) {
            if(businesses.size() > 1) {
                listBusinesses(player, businesses);
                return false;
            }
            business = businesses.getFirst();
            if(args.length == 1) numberArg = args[0];
        } else if(args.length <= 2) {
            business = SneakyVaults.getInstance().sharedVaultManager.getBusiness(args[0]);
            if(business == null || !business.isMember(character.uuid())) {
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&c" + character.name() + " is not part of that business."));
                return false;
            }
            if(args.length == 2) numberArg = args[1];
        } else {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cInvalid Usage: " + this.usageMessage));
            return false;
        }

        int vaultNumber = 1;
        if(numberArg != null) {
            if(!isNumber(numberArg)) {
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cInvalid Number! " + this.usageMessage));
                return false;
            }
            vaultNumber = Integer.parseInt(numberArg);
        }

        if(vaultNumber < 1 || vaultNumber > business.getVaultCount()) {
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&c" + business.getName() + " has vaults 1 to " + business.getVaultCount() + "."));
            return false;
        }

        SharedVaultManager manager = SneakyVaults.getInstance().sharedVaultManager;
        int number = vaultNumber;
        long loading = SneakyVaults.getInstance().probe.start();
        CompletableFuture<SharedVault> load;
        try {
            load = manager.getBusinessVault(business, vaultNumber, true);
        } finally {
            SneakyVaults.getInstance().probe.end("vault.business.load", loading);
        }

        load.whenComplete((vault, error) -> {
            // Runs on the server thread, now or once the vault has been read.
            if(error != null) {
                SneakyVaults.LOGGER.log(Level.SEVERE, "Refusing to open unreadable business vault " + number + " of " + business.getId(), PersistenceQueue.cause(error));
                if(player.isOnline())
                    player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThat vault could not be read safely. A server maintainer has been notified."));
                return;
            }
            if(vault == null) return;
            // While the vault was read the player may have left or switched character, or the
            // business may have been deleted or the character removed from it.
            CharacterHook.CharacterRef current = player.isOnline() ? CharacterHook.current(player) : null;
            if(current == null || !current.uuid().equals(character.uuid()) || !manager.isCurrent(business) || !business.isMember(current.uuid())
                    // An admin may have lowered the vault count meanwhile.
                    || number > business.getVaultCount()) {
                manager.release(vault);
                return;
            }

            long shown = SneakyVaults.getInstance().probe.start();
            try {
                player.openInventory(vault.getInventory());
            } finally {
                SneakyVaults.getInstance().probe.end("vault.business.open-inventory", shown);
            }
        });
        return false;
    }

    private static boolean isNumber(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch(NumberFormatException e) {
            return false;
        }
    }

    private void listBusinesses(Player player, List<Business> businesses) {
        StringBuilder message = new StringBuilder("&eYour businesses:");
        for(Business business : businesses)
            message.append("\n&b- ").append(business.getId()).append(" &7(").append(business.getName()).append(")");
        message.append("\n&eUse ").append(this.usageMessage);
        player.sendMessage(TextUtility.INSTANCE.convertToComponent(message.toString()));
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) throws IllegalArgumentException {
        if(args.length == 1 && sender instanceof Player player && SneakyVaults.getInstance().hasCharacters()) {
            CharacterHook.CharacterRef character = CharacterHook.current(player);
            if(character != null)
                return SneakyVaults.getInstance().sharedVaultManager.getBusinessesOf(character.uuid()).stream().map(Business::getId).toList();
        }
        return List.of();
    }
}
