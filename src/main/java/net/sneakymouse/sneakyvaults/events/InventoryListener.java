package net.sneakymouse.sneakyvaults.events;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import net.sneakymouse.sneakyvaults.types.TemplateVault;
import net.sneakymouse.sneakyvaults.utlitiy.ChatUtility;
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

import java.io.IOException;
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
        if(inventory.getHolder() instanceof PlayerVault playerVault){
            try {
                playerVault.saveVault();
                playerVault.isOpened = false;
            } catch (IOException e){
                SneakyVaults.LOGGER.severe("Error: Failed to save player vault! " + playerVault.getOwner());
            }
        }
        else if(inventory.getHolder() instanceof TemplateVault templateVault){
            if(templateVault.isEditMode()){
                templateVault.endEditMode(event.getInventory());
                event.getPlayer().sendMessage(ChatUtility.convertToComponent("&aEdit session ended! Inventory saved to config!"));
            }else{
                templateVault.onClosed((Player) event.getPlayer(), event.getInventory());
            }
        }
    }

}
