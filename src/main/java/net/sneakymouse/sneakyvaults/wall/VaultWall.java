package net.sneakymouse.sneakyvaults.wall;

import com.sneakyutils.SneakyUtils;
import com.sneakyutils.triggers.BlockKey;
import com.sneakyutils.triggers.ClickOptions;
import com.sneakyutils.triggers.Subscription;
import com.sneakyutils.triggers.Triggers;
import com.sneakyutils.util.TextUtility;
import com.sneakyutils.util.Toasts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.managers.PlayerVaultOpener;
import net.sneakymouse.sneakyvaults.persistence.AtomicYamlFiles;
import net.sneakymouse.sneakyvaults.persistence.PersistenceQueue;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * The bank's vault wall (was MagicSpells {@code spells-city-vaults.yml}): right-clicking a configured block opens a
 * global vault, offers it for sale, or says what is missing. Configured in {@code vault-wall.yml}; ownership and
 * bank balances are SneakyVariables player variables. Server thread only.
 */
public final class VaultWall implements Listener {

    public static final String FILE = "vault-wall.yml";
    private static final String LABEL = "sneakyvaults:vault-wall";
    private static final long REQUIREMENT_MESSAGE_COOLDOWN_MS = 3000;
    private static final long OPEN_AFTER_BUY_TICKS = 5;

    private final SneakyVaults plugin;
    /** Null while SneakyVariables isn't enabled: then the wall stays off. */
    private final @Nullable WallVariables variables;
    private WallConfig config = WallConfig.parse(null);
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final Map<Player, Long> lastRequirementMessage = new WeakHashMap<>();
    /** Bumped on every reload so a slow read can't apply after a newer one. */
    private long generation = 0;

    public VaultWall(SneakyVaults plugin, @Nullable WallVariables variables) {
        this.plugin = plugin;
        this.variables = variables;
    }

    public WallConfig config() {
        return config;
    }

    public boolean hasVariables() {
        return variables != null;
    }

    // --- Loading -----------------------------------------------------------------------------------------------

    /** On enable: read {@link #FILE} (writing the default first if missing) and apply it. */
    public void loadNow() {
        try {
            apply(read());
        } catch(IOException e) {
            SneakyVaults.LOGGER.log(Level.SEVERE, FILE + " could not be read; the vault wall is off.", e);
            apply(WallConfig.parse(null));
        }
    }

    /**
     * Read {@link #FILE} off the server thread and apply it.
     * @return Completes on the server thread with the problems found (empty when all is well).
     */
    public CompletableFuture<List<String>> reload() {
        long mine = ++generation;
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        plugin.persistence.submit(this::read).whenComplete((parsed, error) -> plugin.serverThread.execute(() -> {
            if(mine != generation) {
                done.complete(List.of("A newer reload replaced this one."));
                return;
            }
            if(error != null) {
                SneakyVaults.LOGGER.log(Level.SEVERE, FILE + " could not be read; keeping the previous vault wall.", PersistenceQueue.cause(error));
                done.complete(List.of(FILE + " could not be read (" + PersistenceQueue.cause(error).getMessage() + "); kept the previous settings."));
                return;
            }
            done.complete(apply(parsed));
        }));
        return done;
    }

    /** IO thread or enable. */
    private WallConfig read() throws IOException {
        File file = new File(plugin.getDataFolder(), FILE);
        if(!file.exists()) {
            try(InputStream resource = plugin.getResource(FILE)) {
                if(resource == null) throw new IOException("The embedded " + FILE + " is missing");
                AtomicYamlFiles.saveResource(resource, file.toPath());
            }
        }
        return WallConfig.parse(AtomicYamlFiles.load(file));
    }

    /** Server thread: replace the settings, variable definitions and click subscriptions. */
    private List<String> apply(WallConfig parsed) {
        closeSubscriptions();
        config = parsed;
        List<String> problems = new ArrayList<>(parsed.errors());

        if(variables == null) {
            if(parsed.enabled()) problems.add("SneakyVariables is not enabled, so the vault wall is off.");
        } else {
            Set<String> owned = new LinkedHashSet<>();
            Set<String> readOnly = new LinkedHashSet<>();
            if(parsed.discountVariable() != null) readOnly.add(parsed.discountVariable());
            for(WallChest chest : parsed.chests().values()) {
                if(chest.ownedVariable() != null) owned.add(chest.ownedVariable());
                if(chest.requires() != null) readOnly.add(chest.requires().variable());
            }
            // Currencies belong to the bank: not defined here, only checked when spent.
            variables.define(owned, readOnly);

            if(parsed.enabled()) subscribe(parsed, problems);
        }

        for(String problem : problems) SneakyVaults.LOGGER.warning(FILE + ": " + problem);
        return problems;
    }

    private void subscribe(WallConfig parsed, List<String> problems) {
        Triggers triggers = SneakyUtils.getTriggers();
        ClickOptions options = ClickOptions.builder()
                .cancelEvent(true)
                // The bank region may deny chest use; the wall must still answer the click.
                .ignoreCancelled(false)
                .label(LABEL)
                .build();
        for(WallChest chest : parsed.chests().values()) {
            World world = Bukkit.getWorld(chest.world());
            if(world == null) {
                problems.add("chests." + chest.id() + ": world '" + chest.world() + "' is not loaded; skipped");
                continue;
            }
            subscriptions.add(triggers.onBlockClick(plugin, new BlockKey(world.getUID(), chest.x(), chest.y(), chest.z()),
                    (player, block, side, event) -> {
                        click(player, chest);
                        return true;
                    }, options));
        }
    }

    private void closeSubscriptions() {
        for(Subscription subscription : subscriptions) subscription.close();
        subscriptions.clear();
    }

    /** On disable. */
    public void close() {
        generation++;
        closeSubscriptions();
        if(variables != null) variables.undefine();
        for(Player player : Bukkit.getOnlinePlayers())
            if(player.getOpenInventory().getTopInventory().getHolder(false) instanceof BuyMenu)
                player.closeInventory();
    }

    // --- Clicks ------------------------------------------------------------------------------------------------

    private void click(Player player, WallChest chest) {
        if(variables == null) return;
        boolean owned = chest.ownedVariable() != null && variables.get(player, chest.ownedVariable()) >= 1;
        double requirement = chest.requires() != null ? variables.get(player, chest.requires().variable()) : 0;
        switch(WallRules.decide(chest, owned, requirement)) {
            case BLOCKED -> blocked(player, chest);
            case OFFER -> offer(player, chest);
            case OPEN -> open(player, chest, false);
            case OPEN_EXISTING -> open(player, chest, true);
        }
    }

    private void blocked(Player player, WallChest chest) {
        long now = System.currentTimeMillis();
        Long last = lastRequirementMessage.get(player);
        if(last != null && now - last < REQUIREMENT_MESSAGE_COOLDOWN_MS) return;
        lastRequirementMessage.put(player, now);
        play(player, config.failSound());
        toast(player, chest.requires() != null ? chest.requires().message() : config.messages().requirement());
    }

    private void open(Player player, WallChest chest, boolean existingOnly) {
        play(player, config.openSound());
        String missing = existingOnly ? config.messages().notAvailable() : config.messages().missing();
        PlayerVaultOpener.open(player, chest.vault(), existingOnly, p -> true, missing);
    }

    /** The price this player pays for the chest now. */
    public long priceFor(Player player, WallChest chest) {
        if(chest.price() == null) return 0;
        double discount = variables != null && config.discountVariable() != null ? variables.get(player, config.discountVariable()) : 0;
        return WallRules.price(chest.price().amount(), discount);
    }

    private void offer(Player player, WallChest chest) {
        WallConfig.Menu menu = config.menu();
        BuyMenu holder = new BuyMenu(chest, menu.confirm().slot(), menu.cancel().slot());
        Inventory inventory = Bukkit.createInventory(holder, 9, TextUtility.INSTANCE.convertToComponent(menu.title()));
        holder.setInventory(inventory);
        String price = Long.toString(priceFor(player, chest));
        String currency = chest.price() != null ? chest.price().label() : "";
        if(menu.background().slot() >= 0) inventory.setItem(menu.background().slot(), item(menu.background(), price, currency));
        inventory.setItem(menu.confirm().slot(), item(menu.confirm(), price, currency));
        inventory.setItem(menu.cancel().slot(), item(menu.cancel(), price, currency));
        play(player, config.openSound());
        player.openInventory(inventory);
    }

    @EventHandler(ignoreCancelled = false)
    public void onMenuClick(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder(false) instanceof BuyMenu menu)) return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)) return;
        if(event.getClickedInventory() != event.getView().getTopInventory()) return;
        boolean confirm = event.getSlot() == menu.confirmSlot;
        if(!confirm && event.getSlot() != menu.cancelSlot) return;
        // Closing during the click event is unsafe; do it, and the purchase, on the next tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if(!player.isConnected() || player.getOpenInventory().getTopInventory().getHolder(false) != menu) return;
            player.closeInventory();
            if(confirm) buy(player, menu.chest.id());
            else play(player, config.failSound());
        });
    }

    @EventHandler(ignoreCancelled = false)
    public void onMenuDrag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder(false) instanceof BuyMenu) event.setCancelled(true);
    }

    private void buy(Player player, String chestId) {
        // The menu may have been open across a reload or a purchase elsewhere: use the current settings and check again.
        WallChest chest = config.chests().get(chestId);
        if(variables == null || chest == null || chest.price() == null || chest.ownedVariable() == null) return;
        if(!config.enabled()) return;
        if(variables.get(player, chest.ownedVariable()) >= 1) {
            open(player, chest, false);
            return;
        }
        String currency = chest.price().currency();
        if(!variables.isLoaded(player) || !variables.isDefined(currency) || !variables.isDefined(chest.ownedVariable())) {
            if(!variables.isDefined(currency))
                SneakyVaults.LOGGER.warning(FILE + ": currency variable '" + currency + "' of chest " + chest.id() + " is not defined by any plugin.");
            play(player, config.failSound());
            player.sendMessage(TextUtility.INSTANCE.convertToComponent(config.messages().notLoaded()));
            return;
        }
        long price = priceFor(player, chest);
        if(variables.get(player, currency) < price) {
            play(player, config.failSound());
            toast(player, config.messages().tooPoor());
            return;
        }
        variables.add(player, currency, -price);
        variables.set(player, chest.ownedVariable(), 1);
        SneakyVaults.LOGGER.info(player.getName() + " (" + player.getUniqueId() + ") bought vault " + chest.vault()
                + " (" + chest.id() + ") for " + price + " " + currency);
        play(player, config.buySound());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if(player.isConnected()) open(player, chest, false);
        }, OPEN_AFTER_BUY_TICKS);
    }

    // --- Admin -------------------------------------------------------------------------------------------------

    /** Mark a chest as bought (or not) for an online player. False when the chest has no owned variable. */
    public boolean setOwned(Player player, WallChest chest, boolean owned) {
        if(variables == null || chest.ownedVariable() == null) return false;
        variables.set(player, chest.ownedVariable(), owned ? 1 : 0);
        return true;
    }

    public boolean isOwned(Player player, WallChest chest) {
        return variables != null && chest.ownedVariable() != null && variables.get(player, chest.ownedVariable()) >= 1;
    }

    public boolean isLoaded(Player player) {
        return variables != null && variables.isLoaded(player);
    }

    // --- Presentation ------------------------------------------------------------------------------------------

    private static void play(Player player, WallConfig.Sound sound) {
        if(sound.key().isBlank()) return;
        player.playSound(player.getLocation(), sound.key().toLowerCase(Locale.ROOT), sound.volume(), sound.pitch());
    }

    private void toast(Player player, String text) {
        if(text.isBlank()) return;
        WallConfig.Icon icon = config.toastIcon();
        Material material = Material.matchMaterial(icon.material());
        if(material == null || !material.isItem() || material.isAir()) material = Material.JIGSAW;
        Toasts.show(player, text, material, key(icon.itemModel()));
    }

    private static ItemStack item(WallConfig.MenuItem spec, String price, String currency) {
        Material material = Material.matchMaterial(spec.icon().material());
        if(material == null || !material.isItem() || material.isAir()) material = Material.PAPER;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        NamespacedKey model = key(spec.icon().itemModel());
        if(model != null) meta.setItemModel(model);
        if(spec.name().isEmpty() && spec.lore().isEmpty()) {
            meta.setHideTooltip(true);
        } else {
            meta.displayName(text(spec.name(), price, currency));
            List<Component> lore = new ArrayList<>();
            for(String line : spec.lore()) lore.add(text(line, price, currency));
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static Component text(String line, String price, String currency) {
        return TextUtility.INSTANCE.convertToComponent(line.replace("{price}", price).replace("{currency}", currency))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static @Nullable NamespacedKey key(@Nullable String id) {
        return id == null ? null : NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
    }
}
