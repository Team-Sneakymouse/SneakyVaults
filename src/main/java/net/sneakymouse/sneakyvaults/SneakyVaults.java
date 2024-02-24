package net.sneakymouse.sneakyvaults;

import net.sneakymouse.sneakyvaults.commands.player.CommandOpenVault;
import net.sneakymouse.sneakyvaults.events.InventoryListener;
import net.sneakymouse.sneakyvaults.managers.VaultManager;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Logger;

public class SneakyVaults extends JavaPlugin {

    public static Logger LOGGER;
    private static SneakyVaults instance;
    public static final String IDENTIFIER = "sneakyvaults";

    public File playerDataFolder;

    public VaultManager vaultManager;

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
            getServer().getPluginManager().addPermission(new Permission(IDENTIFIER + ".slots." + 9*i));
        }

        saveDefaultConfig();

        //Load Managers
        vaultManager = new VaultManager();

        //Loading Commands
        getServer().getCommandMap().register(IDENTIFIER, new CommandOpenVault());

        //Loading Events
        getServer().getPluginManager().registerEvents(new InventoryListener(), this);
    }

    @Override
    public void onDisable() {
        vaultManager.saveAllVaults();
    }

    public static SneakyVaults getInstance(){
        return instance;
    }
}
