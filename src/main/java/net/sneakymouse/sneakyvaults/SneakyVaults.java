package net.sneakymouse.sneakyvaults;

import net.sneakymouse.sneakyvaults.commands.admin.*;
import net.sneakymouse.sneakyvaults.commands.player.CommandBusinessVault;
import net.sneakymouse.sneakyvaults.commands.player.CommandCharacterVault;
import net.sneakymouse.sneakyvaults.commands.player.CommandOpenVault;
import net.sneakymouse.sneakyvaults.events.CoreProtectLoggerEvents;
import net.sneakymouse.sneakyvaults.events.EditSessionListener;
import net.sneakymouse.sneakyvaults.events.InventoryListener;
import net.sneakymouse.sneakyvaults.integration.CharacterHook;
import net.sneakymouse.sneakyvaults.managers.SharedVaultManager;
import net.sneakymouse.sneakyvaults.managers.VaultManager;
import net.sneakymouse.sneakyvaults.persistence.AtomicYamlFiles;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SneakyVaults extends JavaPlugin {

    public static Logger LOGGER;
    private static SneakyVaults instance;
    public static final String IDENTIFIER = "sneakyvaults";

    public File playerDataFolder;

    public VaultManager vaultManager;
    public SharedVaultManager sharedVaultManager;
    private boolean characters = false;

    public Plugin msPlugin = null;

    @Override
    public void onEnable() {
        LOGGER = this.getLogger();
        instance = this;

        //Data Setup
        if(!this.getDataFolder().exists())
            if(!this.getDataFolder().mkdir())
                LOGGER.severe("Failed to create Plugin Data folder!");

        playerDataFolder = new File(this.getDataFolder().getPath() + "/PlayerData");

        if(!playerDataFolder.exists())
            if(!playerDataFolder.mkdir()){
                LOGGER.severe("Failed to create Player Data folder!");
                getServer().getPluginManager().disablePlugin(this); //Can't store/read player data, so plugin should not be active!
                return;
            }

        for(int i = 1; i <= 6; i++){
            //Possible Inventory Sizes: 9, 18, 27, 36, 45, 54
            // FALSE so ops do not get max-size vaults without an explicit grant
            getServer().getPluginManager().addPermission(new Permission(IDENTIFIER + ".slots." + 9*i, PermissionDefault.FALSE));
            getServer().getPluginManager().addPermission(new Permission(SharedVaultManager.CHARACTER_SLOTS_PREFIX + 9*i, PermissionDefault.FALSE));
        }

        msPlugin = Bukkit.getPluginManager().getPlugin("MagicSpells");

        saveDefaultConfig();

        //Load Managers
        vaultManager = new VaultManager();
        sharedVaultManager = new SharedVaultManager(getDataFolder());
        characters = getServer().getPluginManager().isPluginEnabled("SneakyCharacterManager");
        if(!characters)
            LOGGER.warning("SneakyCharacterManager is not enabled; character and business vaults are unavailable.");

        //Loading Commands
        getServer().getCommandMap().register(IDENTIFIER, new CommandOpenVault());
        getServer().getCommandMap().register(IDENTIFIER, new CommandPeekVault());
        getServer().getCommandMap().register(IDENTIFIER, new CommandTemplates());
        getServer().getCommandMap().register(IDENTIFIER, new CommandOpenTemplate());
        getServer().getCommandMap().register(IDENTIFIER, new CommandForceConversion());
        getServer().getCommandMap().register(IDENTIFIER, new CommandSearchVault());
        getServer().getCommandMap().register(IDENTIFIER, new CommandCharacterVault());
        getServer().getCommandMap().register(IDENTIFIER, new CommandBusinessVault());
        getServer().getCommandMap().register(IDENTIFIER, new CommandBusiness());
        getServer().getCommandMap().register(IDENTIFIER, new CommandPeekCharacterVault());

        //Loading Events
        getServer().getPluginManager().registerEvents(new InventoryListener(), this);
        getServer().getPluginManager().registerEvents(new EditSessionListener(), this);

        if(Bukkit.getServer().getPluginManager().isPluginEnabled("CoreProtect"))
            getServer().getPluginManager().registerEvents(new CoreProtectLoggerEvents(), this);

        if(characters)
            getServer().getPluginManager().registerEvents(new CharacterHook(), this);
    }

    @Override
    public void onDisable() {
        if(vaultManager != null) vaultManager.saveAllVaults();
        if(sharedVaultManager != null) sharedVaultManager.saveAll();
    }

    /** True when SneakyCharacterManager is enabled, which character and business vaults need. */
    public boolean hasCharacters() {
        return characters;
    }

    @Override
    public void saveConfig() {
        try {
            AtomicYamlFiles.save(getConfig(), getDataFolder().toPath().resolve("config.yml"));
        } catch(IOException exception) {
            getLogger().log(Level.SEVERE, "Could not save config.yml atomically", exception);
        }
    }

    @Override
    public void saveDefaultConfig() {
        Path target = getDataFolder().toPath().resolve("config.yml");
        if(Files.exists(target)) return;

        try(InputStream resource = getResource("config.yml")) {
            if(resource == null)
                throw new IllegalArgumentException("The embedded config.yml resource is missing");

            AtomicYamlFiles.saveResource(resource, target);
        } catch(IOException exception) {
            getLogger().log(Level.SEVERE, "Could not save the default config.yml atomically", exception);
        }
    }

    public static SneakyVaults getInstance(){
        return instance;
    }
}
