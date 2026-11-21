package net.sneakymouse.sneakyvaults.managers;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.integration.Probe;
import net.sneakymouse.sneakyvaults.persistence.PersistenceQueue;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import com.sneakyutils.util.TextUtility;
import org.bukkit.entity.Player;

import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Opens a player's own global vault for them: used by {@code /openvault} and the vault wall.
 * A vault that is not loaded yet is read in the background and opened on a later tick.
 */
public final class PlayerVaultOpener {

    private PlayerVaultOpener() {}

    /**
     * Server thread.
     * @param existingOnly Only open a vault that was saved before; never create one.
     * @param stillAllowed Checked again once the vault has loaded, since that can take a while.
     * @param missingMessage Sent when the player may not have the vault, or (with existingOnly) never had it.
     */
    public static void open(Player player, int number, boolean existingOnly, Predicate<Player> stillAllowed, String missingMessage) {
        SneakyVaults plugin = SneakyVaults.getInstance();
        VaultManager vaults = plugin.vaultManager;
        String playerUUID = player.getUniqueId().toString();
        long request = plugin.beginOpen(player);
        (existingOnly ? vaults.getExistingPlayerVault(playerUUID, number) : vaults.getPlayerVault(playerUUID, number)).whenComplete((vault, error) -> {
            // Runs on the server thread, now or once the vault has been read. By then the player may have
            // left, opened something newer, or lost access.
            if(!player.isConnected() || !plugin.isLatestOpen(player, request) || !stillAllowed.test(player)) return;
            if(error != null){
                SneakyVaults.LOGGER.log(Level.SEVERE, "Refusing to open unreadable vault " + number + " for " + playerUUID, PersistenceQueue.cause(error));
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&cThat vault could not be read safely. A server maintainer has been notified."));
                return;
            }
            // Permissions may have changed while the vault was read.
            if(vault == null || number > vaults.getMaxAllowedVaults(playerUUID)){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent(missingMessage));
                return;
            }
            if(vault.isOpened){
                player.sendMessage(TextUtility.INSTANCE.convertToComponent("&4That vault is currently opened.. Try again later."));
                return;
            }
            vault.growTo(vaults.getMaxVaultSize(playerUUID));
            // Another plugin can cancel the open; then the vault must not stay marked as opened.
            if(show(player, vault))
                vault.isOpened = true;
        });
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
