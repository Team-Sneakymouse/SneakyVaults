package net.sneakymouse.sneakyvaults.events;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import net.sneakymouse.sneakyvaults.types.SharedVault;
import net.sneakymouse.sneakyvaults.types.TemplateVault;
import com.sneakyutils.util.TextUtility;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public class InventoryListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if(SneakyVaults.getInstance().msPlugin == null) return;
        ItemStack clickedItem = e.getCurrentItem();

        if(clickedItem == null || clickedItem.getType() == Material.AIR) return;

        NamespacedKey key = new NamespacedKey(SneakyVaults.getInstance().msPlugin, "magicitem");
        PersistentDataContainer pdc = clickedItem.getItemMeta().getPersistentDataContainer();
        if(!pdc.has(key, PersistentDataType.STRING)) return;
        List<String> bannedMSItems = SneakyVaults.getInstance().getConfig().getStringList("banned_ms_items");
        for(String bannedMSItem : bannedMSItems) {
            String msItem = pdc.get(key, PersistentDataType.STRING);
            if(msItem == null || msItem.isEmpty()) continue;
            if(msItem.equalsIgnoreCase(bannedMSItem)) {
                e.setCancelled(true);
                break;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event){
        Inventory inventory = event.getInventory();
        // Saves are queued off the server thread; failures are logged and reported to the player later.
        SneakyVaults plugin = SneakyVaults.getInstance();
        if(inventory.getHolder() instanceof PlayerVault playerVault){
            try {
                plugin.reportSaveFailure(
                        playerVault.save(),
                        "vault " + playerVault.getVaultNumber() + " for " + playerVault.getOwner(),
                        event.getPlayer()
                );
            } finally {
                playerVault.isOpened = false;
            }
        }
        else if(inventory.getHolder() instanceof SharedVault sharedVault){
            plugin.reportSaveFailure(plugin.sharedVaultManager.onClose(sharedVault), sharedVault.toString(), event.getPlayer());
        }
        else if(inventory.getHolder() instanceof TemplateVault templateVault){
            if(templateVault.isEditMode()){
                templateVault.endEditMode(event.getInventory());
                event.getPlayer().sendMessage(TextUtility.INSTANCE.convertToComponent("&aEdit session ended! Inventory saved to config!"));
            }else{
                Player player = (Player) event.getPlayer();
                plugin.reportSaveFailure(
                        templateVault.saveCopy(player, event.getInventory()),
                        "template vault for " + player.getUniqueId(),
                        player
                );
            }
        }
    }

}
