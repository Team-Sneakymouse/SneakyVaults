package net.sneakymouse.sneakyvaults.wall;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WallConfigTest {

    private static WallConfig parse(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return WallConfig.parse(config);
    }

    @Test
    void bundledConfigHasTheLiveWall() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        try(InputStream in = getClass().getClassLoader().getResourceAsStream(VaultWall.FILE)) {
            assertNotNull(in);
            config.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        WallConfig wall = WallConfig.parse(config);
        assertEquals(java.util.List.of(), wall.errors());
        assertFalse(wall.enabled(), "ships disabled until the MagicSpells spells are removed");
        assertEquals("skillTradingTier4A", wall.discountVariable());
        assertEquals(30, wall.chests().size());

        Set<Integer> vaults = new HashSet<>();
        for(WallChest chest : wall.chests().values()) vaults.add(chest.vault());
        for(int i = 1; i <= 8; i++) {
            assertTrue(vaults.contains(i));
            assertTrue(vaults.contains(10 + i));
            assertTrue(vaults.contains(20 + i));
        }
        for(int i = 31; i <= 36; i++) assertTrue(vaults.contains(i));
        assertFalse(vaults.contains(54), "the bag chest is off on Live");

        WallChest penny1 = wall.chests().get("penny-1");
        assertEquals("world", penny1.world());
        assertEquals(5017, penny1.x());
        assertEquals(64, penny1.y());
        assertEquals(5045, penny1.z());
        assertEquals("vaultPenny1", penny1.ownedVariable());
        assertEquals(new WallChest.Price("bankPenny", 100, "Pennies"), penny1.price());
        assertEquals(1000000000L, wall.chests().get("penny-8").price().amount());
        assertEquals(new WallChest.Price("bankGold", 55555, "Gold"), wall.chests().get("gold-8").price());
        assertEquals(28, wall.chests().get("gold-8").vault());

        WallChest skill6 = wall.chests().get("skill-6");
        assertEquals(36, skill6.vault());
        assertEquals("skillExistingTier7A", skill6.requires().variable());
        assertEquals(6, skill6.requires().min());
        assertNull(skill6.price());
        assertEquals(68, skill6.y());
        assertEquals(5040, skill6.z());
    }

    @Test
    void invalidChestsAreSkippedWithAReason() throws Exception {
        WallConfig wall = parse("""
                chests:
                  ok: { location: 'world,1,2,3', vault: 1 }
                  noLocation: { vault: 2 }
                  badLocation: { location: 'world,1,2', vault: 3 }
                  zeroVault: { location: 'world,1,2,4', vault: 0 }
                  sameSpot: { location: 'world,1,2,3', vault: 4 }
                  sameVault: { location: 'world,9,9,9', vault: 1 }
                  priceNoFlag: { location: 'world,1,2,5', vault: 5, price: { currency: bankPenny, amount: 10 } }
                  flagNoPrice: { location: 'world,1,2,6', vault: 6, owned_variable: x }
                  paidExisting: { location: 'world,1,2,7', vault: 7, owned_variable: x, existing_only: true, price: { currency: bankPenny, amount: 1 } }
                  noRequirementVar: { location: 'world,1,2,8', vault: 8, requires: { min: 2 } }
                """);
        assertEquals(Set.of("ok"), wall.chests().keySet());
        assertEquals(9, wall.errors().size(), wall.errors().toString());
    }

    @Test
    void sameVaultDoesNotBlockTheLocationOfTheSkippedChest() throws Exception {
        WallConfig wall = parse("""
                chests:
                  a: { location: 'world,1,1,1', vault: 1 }
                  b: { location: 'world,2,2,2', vault: 1 }
                  c: { location: 'world,2,2,2', vault: 2 }
                """);
        assertEquals(Set.of("a", "c"), wall.chests().keySet());
    }

    @Test
    void requirementMessageDefaultsToTheGlobalOne() throws Exception {
        WallConfig wall = parse("""
                messages: { requirement: 'need it' }
                chests:
                  a: { location: 'world,1,1,1', vault: 1, requires: { variable: v } }
                  b: { location: 'world,2,2,2', vault: 2, requires: { variable: v, min: 4, message: 'own' } }
                """);
        assertEquals(new WallChest.Requirement("v", 1, "need it"), wall.chests().get("a").requires());
        assertEquals(new WallChest.Requirement("v", 4, "own"), wall.chests().get("b").requires());
    }

    @Test
    void emptyConfigIsDisabledWithDefaults() {
        WallConfig wall = WallConfig.parse(null);
        assertFalse(wall.enabled());
        assertTrue(wall.chests().isEmpty());
        assertNull(wall.discountVariable());
        assertEquals(3, wall.menu().confirm().slot());
        assertEquals(5, wall.menu().cancel().slot());
        assertEquals(-1, wall.menu().background().slot());
        assertEquals("lom:buy", wall.buySound().key());
    }

    @Test
    void clashingMenuSlotsFallBack() throws Exception {
        WallConfig wall = parse("""
                menu: { confirm: { slot: 4 }, cancel: { slot: 4 } }
                """);
        assertEquals(3, wall.menu().confirm().slot());
        assertEquals(5, wall.menu().cancel().slot());
        assertEquals(1, wall.errors().size());
    }
}
