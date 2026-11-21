package net.sneakymouse.sneakyvaults.wall;

import com.sneakyutils.util.DisplayOnlyInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * The "Buy Vault?" confirmation: a display-only virtual inventory (no block, skipped by SneakyItems' updater).
 * Every click in it is cancelled by {@link VaultWall}.
 */
public final class BuyMenu implements DisplayOnlyInventoryHolder {

    final WallChest chest;
    final int confirmSlot;
    final int cancelSlot;
    private Inventory inventory;

    BuyMenu(WallChest chest, int confirmSlot, int cancelSlot) {
        this.chest = chest;
        this.confirmSlot = confirmSlot;
        this.cancelSlot = cancelSlot;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
