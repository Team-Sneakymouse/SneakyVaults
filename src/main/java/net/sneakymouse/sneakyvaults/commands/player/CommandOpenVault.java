package net.sneakymouse.sneakyvaults.commands.player;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandBase;
import net.sneakymouse.sneakyvaults.integration.Probe;
import net.sneakymouse.sneakyvaults.persistence.PersistenceQueue;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import com.sneakyutils.util.TextUtility;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.logging.Level;

public class CommandOpenVault extends CommandBase {
    public CommandOpenVault() {
        super("openvault");
        this.setAliases(List.of("ov", "pv", "openv", "ovault", "vault"));
        this.description = "Open your personal vault!";
        this.usageMessage = "/" + this.getName() + " [Vault Number]";
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        Probe probe = SneakyVaults.getInstance().probe;
        long opened = probe.start();
        try {
            return open(sender, args);
        } finally {
            probe.end("vault.player.open", opened);
        }
    }

    /**
     * The command itself; timed as a whole by execute. A vault that is not loaded yet is read in the
     * background and opened on a later tick; building it and openInventory are timed separately.
     */
    private boolean open(@NotNull CommandSender sender, @NotNull String[] args) {

        if(!(sender instanceof Player player)) return false;

        if(args.length > 1){
            player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cInvalid Usage: " + this.usageMessage));
            return false;
        }

        int vaultNumber = 1;
        if(args.length == 1){
            try {
                vaultNumber = Integer.parseInt(args[0]);
            } catch(NumberFormatException e){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cInvalid Number! " + this.usageMessage));
                return false;
            }
            if(vaultNumber <= 0){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cVault number must be 1 or higher!"));
                return false;
            }
        }

        String playerUUID = player.getUniqueId().toString();
        int number = vaultNumber;
        long request = SneakyVaults.getInstance().beginOpen(player);
        SneakyVaults.getInstance().vaultManager.getPlayerVault(playerUUID, vaultNumber).whenComplete((vault, error) -> {
            // Runs on the server thread, now or once the vault has been read. By then the player may have
            // left, opened something newer, or lost the command permission.
            if(!player.isConnected() || !SneakyVaults.getInstance().isLatestOpen(player, request) || !testPermissionSilent(player)) return;
            if(error != null){
                SneakyVaults.LOGGER.log(Level.SEVERE, "Refusing to open unreadable vault " + number + " for " + playerUUID, PersistenceQueue.cause(error));
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThat vault could not be read safely. A server maintainer has been notified."));
                return;
            }
            // Permissions may have changed while the vault was read.
            if(vault == null || number > SneakyVaults.getInstance().vaultManager.getMaxAllowedVaults(playerUUID)){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cCould not find your player vault!"));
                return;
            }
            if(vault.isOpened){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&4That vault is currently opened.. Try again later."));
                return;
            }
            vault.growTo(SneakyVaults.getInstance().vaultManager.getMaxVaultSize(playerUUID));
            // Another plugin can cancel the open; then the vault must not stay marked as opened.
            if(show(player, vault))
                vault.isOpened = true;
        });
        return false;
    }

    /** @return False if another plugin cancelled the open. */
    private static boolean show(Player player, PlayerVault vault) {
        Probe probe = SneakyVaults.getInstance().probe;
        long shown = probe.start();
        try {
            return player.openInventory(vault.getInventory()) != null;
        } finally {
            probe.end("vault.player.open-inventory", shown);
        }
    }
}
