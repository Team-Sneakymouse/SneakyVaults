package net.sneakymouse.sneakyvaults.wall;

import org.jetbrains.annotations.Nullable;

/**
 * One chest of the vault wall: clicking the block at the location opens the player's global vault {@code vault}.
 *
 * <ul>
 *     <li>{@code requires}: the player needs a variable at a minimum value first (a skill rank).</li>
 *     <li>{@code price}: the vault must be bought once; {@code ownedVariable} remembers that it was.</li>
 *     <li>{@code existingOnly}: opens only a vault the player already has saved data for, and never makes a new one.</li>
 *     <li>None of these: free for everyone.</li>
 * </ul>
 */
public record WallChest(
        String id,
        String world, int x, int y, int z,
        int vault,
        @Nullable Price price,
        @Nullable String ownedVariable,
        @Nullable Requirement requires,
        boolean existingOnly
) {

    /** A one-off purchase from a bank variable. {@code label} names the currency in the menu ("Pennies"). */
    public record Price(String currency, long amount, String label) {}

    /** A variable the player needs at {@code min} or more. {@code message} is shown when they don't. */
    public record Requirement(String variable, double min, String message) {}
}
