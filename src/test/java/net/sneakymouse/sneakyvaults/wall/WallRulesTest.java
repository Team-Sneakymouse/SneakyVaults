package net.sneakymouse.sneakyvaults.wall;

import org.junit.jupiter.api.Test;

import static net.sneakymouse.sneakyvaults.wall.WallRules.Outcome.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WallRulesTest {

    private static final WallChest.Price PRICE = new WallChest.Price("bankPenny", 100, "Pennies");
    private static final WallChest.Requirement SKILL = new WallChest.Requirement("skillExistingTier7A", 3, "");

    private static WallChest chest(WallChest.Price price, WallChest.Requirement requires, boolean existingOnly) {
        return new WallChest("c", "world", 0, 0, 0, 1, price, price == null ? null : "vaultPenny1", requires, existingOnly);
    }

    @Test
    void freeChestOpens() {
        assertEquals(OPEN, WallRules.decide(chest(null, null, false), false, 0));
    }

    @Test
    void boughtChestOpensAndUnboughtIsOffered() {
        assertEquals(OFFER, WallRules.decide(chest(PRICE, null, false), false, 0));
        assertEquals(OPEN, WallRules.decide(chest(PRICE, null, false), true, 0));
    }

    @Test
    void requirementNeedsTheMinimum() {
        assertEquals(BLOCKED, WallRules.decide(chest(null, SKILL, false), false, 2));
        assertEquals(OPEN, WallRules.decide(chest(null, SKILL, false), false, 3));
        assertEquals(OPEN, WallRules.decide(chest(null, SKILL, false), false, 10));
    }

    @Test
    void requirementComesBeforeTheOffer() {
        assertEquals(BLOCKED, WallRules.decide(chest(PRICE, SKILL, false), false, 0));
        assertEquals(OFFER, WallRules.decide(chest(PRICE, SKILL, false), false, 3));
    }

    @Test
    void existingOnlyOpensOnlyExisting() {
        assertEquals(OPEN_EXISTING, WallRules.decide(chest(null, null, true), false, 0));
        assertEquals(BLOCKED, WallRules.decide(chest(null, SKILL, true), false, 0));
        assertEquals(OPEN_EXISTING, WallRules.decide(chest(null, SKILL, true), false, 7));
    }

    /**
     * ceil(base * (100 - discount) / 100) computed exactly for every live price. MagicSpells computed it in floating
     * point, which sometimes came out 1 higher (100000000 at 33%: 67000001); the exact value is intended.
     */
    @Test
    void priceMatchesTheOldDiscountFormula() {
        long[] bases = {100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000L,
                25, 500, 2500, 50000, 250000, 1, 5, 55555};
        for(long base : bases)
            for(int discount = 0; discount <= 100; discount++)
                assertEquals(java.math.BigDecimal.valueOf(base * (100 - discount)).divide(java.math.BigDecimal.valueOf(100), 0, java.math.RoundingMode.CEILING).longValueExact(),
                        WallRules.price(base, discount),
                        base + " at " + discount + "%");
    }

    @Test
    void priceExamples() {
        assertEquals(100, WallRules.price(100, 0));
        assertEquals(55000, WallRules.price(55555, 1)); // 54999.45 rounds up
        assertEquals(1, WallRules.price(1, 50));
        assertEquals(0, WallRules.price(1, 100));
    }

    @Test
    void discountIsClampedAndWhole() {
        assertEquals(100, WallRules.price(100, -5));
        assertEquals(0, WallRules.price(100, 250));
        assertEquals(97, WallRules.price(100, 3.9));
        assertEquals(100, WallRules.price(100, Double.NaN));
    }
}
