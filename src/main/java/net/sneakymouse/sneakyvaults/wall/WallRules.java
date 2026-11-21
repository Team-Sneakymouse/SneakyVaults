package net.sneakymouse.sneakyvaults.wall;

/** What a click on a wall chest does, and what a vault costs. No Bukkit state. */
public final class WallRules {

    private WallRules() {}

    public enum Outcome {
        /** The requirement isn't met. */
        BLOCKED,
        /** Not bought yet: offer it. */
        OFFER,
        /** Open the vault, creating it if needed. */
        OPEN,
        /** Open the vault only if it already has saved data. */
        OPEN_EXISTING
    }

    /**
     * @param owned The chest's owned variable is set (ignored for chests without a price).
     * @param requirementValue The player's value of the chest's required variable (ignored without one).
     */
    public static Outcome decide(WallChest chest, boolean owned, double requirementValue) {
        if(chest.requires() != null && requirementValue < chest.requires().min()) return Outcome.BLOCKED;
        if(chest.price() != null && !owned) return Outcome.OFFER;
        return chest.existingOnly() ? Outcome.OPEN_EXISTING : Outcome.OPEN;
    }

    /**
     * The price after a discount of {@code discountPercent} percent, rounded up as the old MagicSpells
     * vault discount did: {@code ceil(amount * (100 - discount) / 100)}. The discount is taken as a whole
     * number between 0 and 100.
     */
    public static long price(long amount, double discountPercent) {
        if(Double.isNaN(discountPercent)) discountPercent = 0;
        long discount = (long) Math.max(0, Math.min(100, Math.floor(discountPercent)));
        long keep = 100 - discount;
        // ceil(amount * keep / 100) without floating point; amount * keep fits a long for any price we use.
        return Math.ceilDiv(Math.multiplyExact(amount, keep), 100L);
    }
}
