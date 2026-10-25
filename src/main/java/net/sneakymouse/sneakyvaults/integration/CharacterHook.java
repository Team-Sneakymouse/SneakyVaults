package net.sneakymouse.sneakyvaults.integration;

import net.sneakycharactermanager.paper.handlers.character.Character;
import net.sneakycharactermanager.paper.handlers.character.LoadCharacterEvent;
import net.sneakymouse.sneakyvaults.types.SharedVault;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

/**
 * The only class that touches SneakyCharacterManager. Callers must check
 * {@link net.sneakymouse.sneakyvaults.SneakyVaults#hasCharacters()} first.
 */
public final class CharacterHook implements Listener {

    public record CharacterRef(String uuid, String name) {}

    /** The character the player is currently loaded into, or null if none. */
    public static @Nullable CharacterRef current(Player player) {
        Character character = Character.get(player);
        if(character == null) return null;
        return new CharacterRef(character.getCharacterUUID(), character.getNameUnformatted());
    }

    /**
     * Close character and business vaults before the character manager swaps the player's state,
     * so a vault never stays open for the wrong character.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLoadCharacter(LoadCharacterEvent event) {
        Player player = event.getPlayer();
        if(player.getOpenInventory().getTopInventory().getHolder() instanceof SharedVault)
            player.closeInventory();
    }
}
