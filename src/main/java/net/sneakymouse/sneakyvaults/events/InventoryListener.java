package net.sneakymouse.sneakyvaults.events;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

import java.io.IOException;

public class InventoryListener implements Listener {

    @EventHandler
    public void onClose(InventoryCloseEvent event){
        Inventory inventory = event.getInventory();
        if(inventory.getHolder() instanceof PlayerVault playerVault){
            try {
                playerVault.saveVault();
            } catch (IOException e){
                SneakyVaults.LOGGER.severe("Error: Failed to save player vault! " + playerVault.getOwner());
            }
        }
    }

}
