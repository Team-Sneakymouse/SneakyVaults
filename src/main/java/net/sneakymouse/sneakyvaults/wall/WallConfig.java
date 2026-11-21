package net.sneakymouse.sneakyvaults.wall;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The contents of {@code vault-wall.yml}. {@link #parse} skips invalid chests and lists why in {@link #errors()}.
 * Materials and sounds are kept as text and resolved by {@link VaultWall}, so parsing needs no running server.
 */
public record WallConfig(
        boolean enabled,
        @Nullable String discountVariable,
        Map<String, WallChest> chests,
        Messages messages,
        Sound openSound, Sound buySound, Sound failSound,
        Icon toastIcon,
        Menu menu,
        List<String> errors
) {

    public record Messages(String tooPoor, String requirement, String notAvailable, String missing, String notLoaded) {}

    public record Sound(String key, float volume, float pitch) {}

    /** {@code itemModel} is a namespaced {@code item_model} id, or null for the material's own look. */
    public record Icon(String material, @Nullable String itemModel) {}

    /** {@code slot} is -1 for an item that is left out. Lore lines may use {price} and {currency}. */
    public record MenuItem(int slot, Icon icon, String name, List<String> lore) {}

    public record Menu(String title, MenuItem background, MenuItem confirm, MenuItem cancel) {}

    public static WallConfig parse(@Nullable ConfigurationSection root) {
        if(root == null) root = new org.bukkit.configuration.MemoryConfiguration();
        List<String> errors = new ArrayList<>();

        String discount = blankToNull(root.getString("discount_variable"));

        ConfigurationSection m = section(root, "messages");
        Messages messages = new Messages(
                m.getString("too_poor", "&7You are too poor to upgrade this &6Vault&7!"),
                m.getString("requirement", "&7You can't open this &6Vault &7yet."),
                m.getString("not_available", "&7This &6Vault &7isn't available yet."),
                m.getString("missing", "&cCould not find your player vault!"),
                m.getString("not_loaded", "&cYour bank is still loading. Try again in a moment.")
        );

        ConfigurationSection s = section(root, "sounds");
        Sound open = sound(s, "open", "block.chest.open");
        Sound buy = sound(s, "buy", "lom:buy");
        Sound fail = sound(s, "fail", "lom:fail_wrong");

        Icon toastIcon = icon(section(root, "toast_icon"), "JIGSAW");

        ConfigurationSection menuSection = section(root, "menu");
        Menu menu = new Menu(
                menuSection.getString("title", "&6Buy Vault?"),
                menuItem(section(menuSection, "background"), -1, "AIR", "", List.of()),
                menuItem(section(menuSection, "confirm"), 3, "LIME_STAINED_GLASS_PANE", "&aUnlock Vault?",
                        List.of("&eRequires &6{price} {currency}&e!", "&eMoney must be in bank!")),
                menuItem(section(menuSection, "cancel"), 5, "RED_STAINED_GLASS_PANE", "&cNo", List.of())
        );
        if(menu.confirm().slot() < 0 || menu.cancel().slot() < 0 || menu.confirm().slot() == menu.cancel().slot()) {
            errors.add("menu: confirm and cancel need two different slots from 0 to 8; using 3 and 5");
            menu = new Menu(menu.title(), menu.background(),
                    new MenuItem(3, menu.confirm().icon(), menu.confirm().name(), menu.confirm().lore()),
                    new MenuItem(5, menu.cancel().icon(), menu.cancel().name(), menu.cancel().lore()));
        }

        Map<String, WallChest> chests = new LinkedHashMap<>();
        Map<String, String> byLocation = new HashMap<>();
        Map<Integer, String> byVault = new HashMap<>();
        ConfigurationSection chestSection = root.getConfigurationSection("chests");
        if(chestSection != null) {
            for(String id : chestSection.getKeys(false)) {
                ConfigurationSection c = chestSection.getConfigurationSection(id);
                if(c == null) {
                    errors.add("chests." + id + ": not a section");
                    continue;
                }
                try {
                    WallChest chest = chest(id, c, messages.requirement());
                    String where = chest.world() + "," + chest.x() + "," + chest.y() + "," + chest.z();
                    String other = byLocation.putIfAbsent(where, id);
                    if(other != null) throw new IllegalArgumentException("same location as " + other);
                    other = byVault.putIfAbsent(chest.vault(), id);
                    if(other != null) {
                        byLocation.remove(where);
                        throw new IllegalArgumentException("same vault number as " + other);
                    }
                    chests.put(id, chest);
                } catch(IllegalArgumentException e) {
                    errors.add("chests." + id + ": " + e.getMessage() + "; skipped");
                }
            }
        }

        return new WallConfig(root.getBoolean("enabled", false), discount, Collections.unmodifiableMap(chests),
                messages, open, buy, fail, toastIcon, menu, List.copyOf(errors));
    }

    private static WallChest chest(String id, ConfigurationSection c, String defaultRequirementMessage) {
        String location = c.getString("location");
        if(location == null) throw new IllegalArgumentException("no location");
        String[] parts = location.split(",");
        if(parts.length != 4 || parts[0].isBlank())
            throw new IllegalArgumentException("location must be world,x,y,z, got '" + location + "'");
        int x, y, z;
        try {
            x = Integer.parseInt(parts[1].trim());
            y = Integer.parseInt(parts[2].trim());
            z = Integer.parseInt(parts[3].trim());
        } catch(NumberFormatException e) {
            throw new IllegalArgumentException("location must be world,x,y,z, got '" + location + "'");
        }

        int vault = c.getInt("vault", 0);
        if(vault < 1) throw new IllegalArgumentException("vault must be 1 or higher");

        WallChest.Price price = null;
        String owned = blankToNull(c.getString("owned_variable"));
        ConfigurationSection p = c.getConfigurationSection("price");
        if(p != null) {
            String currency = blankToNull(p.getString("currency"));
            long amount = p.getLong("amount", -1);
            if(currency == null) throw new IllegalArgumentException("price needs a currency variable");
            if(amount < 0) throw new IllegalArgumentException("price needs an amount of 0 or more");
            if(amount > Long.MAX_VALUE / 100) throw new IllegalArgumentException("price amount is too large");
            if(owned == null) throw new IllegalArgumentException("a chest with a price needs owned_variable");
            price = new WallChest.Price(currency, amount, p.getString("label", currency));
        } else if(owned != null) {
            throw new IllegalArgumentException("owned_variable without a price");
        }

        WallChest.Requirement requires = null;
        ConfigurationSection r = c.getConfigurationSection("requires");
        if(r != null) {
            String variable = blankToNull(r.getString("variable"));
            if(variable == null) throw new IllegalArgumentException("requires needs a variable");
            requires = new WallChest.Requirement(variable, r.getDouble("min", 1), r.getString("message", defaultRequirementMessage));
        }

        boolean existingOnly = c.getBoolean("existing_only", false);
        if(existingOnly && price != null) throw new IllegalArgumentException("existing_only chests can't have a price");

        return new WallChest(id, parts[0].trim(), x, y, z, vault, price, owned, requires, existingOnly);
    }

    private static ConfigurationSection section(ConfigurationSection parent, String key) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        return section != null ? section : new org.bukkit.configuration.MemoryConfiguration();
    }

    private static Sound sound(ConfigurationSection parent, String key, String defaultKey) {
        if(parent.isString(key)) return new Sound(parent.getString(key, defaultKey), 0.4f, 1.0f);
        ConfigurationSection s = section(parent, key);
        return new Sound(s.getString("sound", defaultKey), (float) s.getDouble("volume", 0.4), (float) s.getDouble("pitch", 1.0));
    }

    private static Icon icon(ConfigurationSection s, String defaultMaterial) {
        return new Icon(s.getString("material", defaultMaterial).toUpperCase(Locale.ROOT), blankToNull(s.getString("item_model")));
    }

    private static MenuItem menuItem(ConfigurationSection s, int defaultSlot, String defaultMaterial, String defaultName, List<String> defaultLore) {
        int slot = s.getInt("slot", defaultSlot);
        if(slot > 8) slot = -1;
        List<String> lore = s.isList("lore") ? s.getStringList("lore") : defaultLore;
        return new MenuItem(slot, icon(s, defaultMaterial), s.getString("name", defaultName), List.copyOf(lore));
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
