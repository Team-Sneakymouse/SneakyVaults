package net.sneakymouse.sneakyvaults.wall;

import com.sneakyvariables.api.Scope;
import com.sneakyvariables.api.SneakyVariables;
import com.sneakyvariables.api.VariableDefinition;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;

/** {@link WallVariables} through SneakyVariables. Only touched while SneakyVariables is enabled. */
public final class SneakyVariablesAccess implements WallVariables {

    private final Plugin plugin;

    private SneakyVariablesAccess(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Call only while SneakyVariables is enabled; returning the interface keeps its classes out of callers. */
    public static WallVariables create(Plugin plugin) {
        return new SneakyVariablesAccess(plugin);
    }

    @Override
    public void define(Collection<String> ownedFlags, Collection<String> readOnly) {
        var api = SneakyVariables.api();
        api.undefineAll(plugin);
        // Same shape as the MagicSpells definitions (permanent player numbers), so both are accepted while both exist.
        for(String name : ownedFlags)
            api.define(plugin, VariableDefinition.number(name, Scope.PLAYER).defaultValue(0).min(0).max(1).build());
        for(String name : readOnly)
            if(!ownedFlags.contains(name))
                api.define(plugin, VariableDefinition.number(name, Scope.PLAYER).defaultValue(0).min(0).build());
    }

    @Override
    public void undefine() {
        if(SneakyVariables.isAvailable()) SneakyVariables.api().undefineAll(plugin);
    }

    @Override
    public boolean isLoaded(Player player) {
        return SneakyVariables.api().player(player).isLoaded();
    }

    @Override
    public boolean isDefined(String name) {
        return SneakyVariables.api().definition(name) != null;
    }

    @Override
    public double get(Player player, String name) {
        return SneakyVariables.api().player(player).getNumber(name);
    }

    @Override
    public double add(Player player, String name, double delta) {
        return SneakyVariables.api().player(player).add(name, delta);
    }

    @Override
    public void set(Player player, String name, double value) {
        SneakyVariables.api().player(player).setNumber(name, value);
    }
}
