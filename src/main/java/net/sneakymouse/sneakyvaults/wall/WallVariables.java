package net.sneakymouse.sneakyvaults.wall;

import org.bukkit.entity.Player;

import java.util.Collection;

/** The player variables the vault wall reads and writes. Server thread only. */
public interface WallVariables {

    /** Registers the ownership flags and the read-only variables the wall uses, replacing earlier ones. */
    void define(Collection<String> ownedFlags, Collection<String> readOnly);

    /** Drops every definition {@link #define} made. */
    void undefine();

    /** False while the player's values are still loading here: reads give defaults and writes are ignored. */
    boolean isLoaded(Player player);

    /** True when some plugin defines the variable, so writes to it are kept. */
    boolean isDefined(String name);

    double get(Player player, String name);

    /** @return The value after the change. */
    double add(Player player, String name, double delta);

    void set(Player player, String name, double value);
}
