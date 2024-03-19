package net.sneakymouse.sneakyvaults.events;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.utlitiy.ChatUtility;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class EditSessionListener implements Listener {

    @EventHandler
    public void onChat(AsyncChatEvent event){
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        Player player = event.getPlayer();

        if(!SneakyVaults.getInstance().vaultManager.vaultSizeSession.containsKey(player.getUniqueId().toString()))
            return;

        event.setCancelled(true);

        if(message.equalsIgnoreCase("cancel")){
            SneakyVaults.getInstance().vaultManager.vaultSizeSession.remove(player.getUniqueId().toString());
            player.sendMessage(ChatUtility.convertToComponent("&eEnded edit session!"));
            return;
        }

        try {
            int vaultSize = Integer.parseInt(message);

            if(vaultSize % 9 != 0 || vaultSize > 54){
                player.sendMessage(ChatUtility.convertToComponent("&cInvalid size! Must be a multiple of 9 and no larger then 54"));
                return;
            }

            FileConfiguration config = SneakyVaults.getInstance().getConfig();
            ConfigurationSection section = config.getConfigurationSection("template_vaults");
            if(section == null) {
                player.sendMessage(ChatUtility.convertToComponent("&eEnded edit session! As no vaults exist!"));
                return;
            }

            String vaultName = SneakyVaults.getInstance().vaultManager.vaultSizeSession.get(player.getUniqueId().toString());
            ConfigurationSection vaultSection = section.getConfigurationSection(vaultName);
            if(vaultSection == null) {
                player.sendMessage(ChatUtility.convertToComponent("&eEnded edit session! As template vault does not exist!"));
                return;
            }

            vaultSection.set("size", vaultSize);
            SneakyVaults.getInstance().saveConfig();
            SneakyVaults.getInstance().vaultManager.vaultSizeSession.remove(player.getUniqueId().toString());
            player.sendMessage(ChatUtility.convertToComponent("&eEnded edit session! Vault size has been set.\n&dStart a new edit session to edit the inventory items!"));

        } catch(NumberFormatException e){
            player.sendMessage(ChatUtility.convertToComponent("&cInvalid Number! Say 'cancel' to end the edit session."));
        }

    }

}
