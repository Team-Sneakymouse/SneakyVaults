package net.sneakymouse.sneakyvaults.events;

import io.papermc.paper.event.inventory.PaperInventoryMoveItemEvent;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.listener.player.InventoryChangeListener;
import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import net.sneakymouse.sneakyvaults.types.TemplateVault;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.bukkit.Bukkit.getServer;

public class CoreProtectLoggerEvents implements Listener {

    Method onInventoryInteract;

    public CoreProtectLoggerEvents() {

        try {
            onInventoryInteract = InventoryChangeListener.class.getDeclaredMethod("onInventoryInteract",
                    String.class, Inventory.class, ItemStack[].class, Material.class, Location.class, boolean.class);
            onInventoryInteract.setAccessible(true);
        } catch (NoSuchMethodException e) {
            SneakyVaults.LOGGER.warning("CoreProtect Logging Disabled due to missing methods!");
        }
    }

    @EventHandler
    public void onInventoryInteract(final InventoryClickEvent event) throws InvocationTargetException, IllegalAccessException {
        if(onInventoryInteract == null) return;

        Inventory inventory = event.getView().getTopInventory();

        if(!(inventory.getHolder() instanceof PlayerVault))
            return;

        if(!(event.getWhoClicked() instanceof Player player)) return;

        InventoryAction inventoryAction = event.getAction();

        //Following CoreProtects structure here
        if (inventoryAction == InventoryAction.NOTHING) return;

        if(event.getInventory().getHolder() instanceof PlayerVault vault) {
            String user = player.getName();
            ItemStack[] contents = inventory.getContents();
            Location location = vault.getDummyLocation();

            onInventoryInteract.invoke(null, user, inventory, contents, null, location, true);
        }
    }

}
