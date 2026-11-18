package net.sneakymouse.sneakyvaults.integration;

import com.sneakyitems.api.SneakyItemsAPI;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The only class that touches SneakyItems. Callers must check {@link #available()} first.
 */
public final class MagicItemHook {

    private MagicItemHook() {}

    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("SneakyItems");
    }

    /** The stamped magic-item name (the {@code magicitem} PDC value), or null when the stack is not a magic item. */
    public static @Nullable String keyOf(ItemStack item) {
        return SneakyItemsAPI.keyOf(item);
    }
}
