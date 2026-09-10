package net.sneakymouse.sneakyvaults.events;

import net.coreprotect.listener.player.InventoryChangeListener;
import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.types.PlayerVault;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryInteract(final InventoryClickEvent event) throws InvocationTargetException, IllegalAccessException {
        if(onInventoryInteract == null) return;

        Inventory inventory = event.getView().getTopInventory();

        if(!(inventory.getHolder() instanceof PlayerVault vault))
            return;

        if(!(event.getWhoClicked() instanceof Player player)) return;

        InventoryAction inventoryAction = event.getAction();

        //Following CoreProtects structure here
        if (inventoryAction == InventoryAction.NOTHING) return;

        String user = player.getName();
        ItemStack[] contents = getInventorySnapshot(inventory);
        Location location = vault.getDummyLocation();

        onInventoryInteract.invoke(null, user, inventory, contents, null, location, true);
    }

    private ItemStack[] getInventorySnapshot(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();

        for(int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if(item != null)
                contents[i] = item.clone();
        }

        return contents;
    }

}
