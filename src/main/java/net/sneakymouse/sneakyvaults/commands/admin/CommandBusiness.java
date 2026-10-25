package net.sneakymouse.sneakyvaults.commands.admin;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.commands.CommandAdminBase;
import net.sneakymouse.sneakyvaults.integration.CharacterHook;
import net.sneakymouse.sneakyvaults.managers.SharedVaultManager;
import net.sneakymouse.sneakyvaults.types.Business;
import net.sneakymouse.sneakyvaults.types.SharedVault;
import com.sneakyutils.util.TextUtility;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class CommandBusiness extends CommandAdminBase {

    private static final List<String> SUBCOMMANDS = List.of("list", "create", "delete", "rename", "add", "remove", "members", "setvaults", "setsize", "open");

    public CommandBusiness() {
        super("business");
        this.setAliases(List.of("businesses"));
        this.description = "Manage business groups and their shared vaults";
        this.usageMessage = "/business <list|create|delete|rename|add|remove|members|setvaults|setsize|open> ...";
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if(!SneakyVaults.getInstance().hasCharacters()) {
            send(sender, "&cBusinesses need SneakyCharacterManager.");
            return false;
        }

        SharedVaultManager manager = SneakyVaults.getInstance().sharedVaultManager;
        if(args.length == 0 || args[0].equalsIgnoreCase("list")) {
            StringBuilder message = new StringBuilder("&eBusinesses:");
            for(Business business : manager.getBusinesses())
                message.append("\n&b- ").append(business.getId()).append(" &7(").append(business.getName()).append(", ")
                        .append(business.getMembers().size()).append(" members, ").append(business.getVaultCount()).append("x")
                        .append(business.getVaultSize()).append(")");
            send(sender, message.toString());
            return false;
        }

        String sub = args[0].toLowerCase();
        if(args.length < 2) {
            send(sender, "&cInvalid Usage: " + usage(sub));
            return false;
        }

        String id = args[1].toLowerCase();
        try {
            if(sub.equals("create")) {
                if(!Business.ID_PATTERN.matcher(id).matches()) {
                    send(sender, "&cBusiness ids use a-z, 0-9, _ and -, up to 32 characters.");
                    return false;
                }
                String name = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : id;
                if(manager.createBusiness(id, name) == null)
                    send(sender, "&cBusiness " + id + " already exists.");
                else
                    send(sender, "&eCreated business " + id + " &7(" + name + ")&e. Add members with /business add " + id + " <player>");
                return false;
            }

            Business business = manager.getBusiness(id);
            if(business == null) {
                send(sender, "&cNo business called " + id + ".");
                return false;
            }

            switch(sub) {
                case "delete" -> {
                    if(args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                        send(sender, "&cThis removes " + id + " and all its vaults. Type /business delete " + id + " confirm");
                        return false;
                    }
                    manager.deleteBusiness(business);
                    send(sender, "&eDeleted business " + id + ". Its file was moved to Businesses/deleted.");
                }
                case "rename" -> {
                    if(args.length < 3) { send(sender, "&cInvalid Usage: " + usage(sub)); return false; }
                    business.setName(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                    business.save();
                    send(sender, "&eRenamed " + id + " to " + business.getName() + ".");
                }
                case "add" -> {
                    if(args.length != 3) { send(sender, "&cInvalid Usage: " + usage(sub)); return false; }
                    Player target = Bukkit.getPlayer(args[2]);
                    if(target == null) { send(sender, "&cThat player is not online."); return false; }
                    CharacterHook.CharacterRef character = CharacterHook.current(target);
                    if(character == null) { send(sender, "&c" + target.getName() + " is not playing a character."); return false; }
                    if(business.isMember(character.uuid())) { send(sender, "&c" + character.name() + " is already in " + id + "."); return false; }
                    business.getMembers().put(character.uuid(), new Business.Member(character.name(), target.getUniqueId().toString()));
                    business.save();
                    send(sender, "&eAdded " + character.name() + " (" + target.getName() + ") to " + business.getName() + ".");
                    send(target, "&a" + character.name() + " has joined " + business.getName() + "! Use /bvault to open its vaults.");
                }
                case "remove" -> {
                    if(args.length != 3) { send(sender, "&cInvalid Usage: " + usage(sub)); return false; }
                    String characterUUID = findMember(business, args[2]);
                    if(characterUUID == null) { send(sender, "&cNo member of " + id + " matches " + args[2] + "."); return false; }
                    Business.Member member = business.getMembers().remove(characterUUID);
                    business.save();
                    kickIfPlaying(manager, business, characterUUID);
                    send(sender, "&eRemoved " + member.characterName() + " from " + business.getName() + ".");
                }
                case "members" -> {
                    StringBuilder message = new StringBuilder("&eMembers of " + business.getName() + ":");
                    for(Map.Entry<String, Business.Member> entry : business.getMembers().entrySet()) {
                        String playerName = playerName(entry.getValue().playerUUID());
                        message.append("\n&b- ").append(entry.getValue().characterName()).append(" &7(").append(playerName)
                                .append(", ").append(entry.getKey()).append(")");
                    }
                    send(sender, message.toString());
                }
                case "setvaults" -> {
                    Integer count = parse(sender, args, 1, 54);
                    if(count == null) return false;
                    business.setVaultCount(count);
                    business.save();
                    send(sender, "&e" + business.getName() + " now has " + count + " vaults. Existing items in higher vaults are kept.");
                }
                case "setsize" -> {
                    Integer size = parse(sender, args, 9, 54);
                    if(size == null) return false;
                    if(size % 9 != 0) { send(sender, "&cSize must be a multiple of 9."); return false; }
                    business.setVaultSize(size);
                    business.save();
                    send(sender, "&e" + business.getName() + " vaults are now " + size + " slots. Vaults never shrink below their saved size.");
                }
                case "open" -> {
                    if(!(sender instanceof Player player)) { send(sender, "&cOnly players can open vaults."); return false; }
                    int vaultNumber = 1;
                    if(args.length > 2) {
                        Integer parsed = parse(sender, args, 1, Integer.MAX_VALUE);
                        if(parsed == null) return false;
                        vaultNumber = parsed;
                    }
                    SharedVault vault = manager.getBusinessVault(business, vaultNumber, false);
                    if(vault == null) { send(sender, "&cThat vault has never been used."); return false; }
                    player.openInventory(vault.getInventory());
                }
                default -> send(sender, "&cInvalid Usage: " + this.usageMessage);
            }
        } catch(IOException e) {
            SneakyVaults.LOGGER.log(Level.SEVERE, "/business " + String.join(" ", args) + " failed", e);
            send(sender, "&cThat failed and nothing was changed on disk. Check the server log.");
        }
        return false;
    }

    /** Match a member by character UUID, character name, or the name of an online player playing them. */
    private static String findMember(Business business, String query) {
        if(business.isMember(query)) return query;
        for(Map.Entry<String, Business.Member> entry : business.getMembers().entrySet())
            if(entry.getValue().characterName().equalsIgnoreCase(query)) return entry.getKey();
        Player player = Bukkit.getPlayer(query);
        if(player != null) {
            CharacterHook.CharacterRef character = CharacterHook.current(player);
            if(character != null && business.isMember(character.uuid())) return character.uuid();
        }
        return null;
    }

    private static void kickIfPlaying(SharedVaultManager manager, Business business, String characterUUID) {
        for(Player player : Bukkit.getOnlinePlayers()) {
            CharacterHook.CharacterRef character = CharacterHook.current(player);
            if(character != null && character.uuid().equals(characterUUID))
                manager.closeBusinessVaults(business, player);
        }
    }

    private static String playerName(String playerUUID) {
        try {
            String name = Bukkit.getOfflinePlayer(UUID.fromString(playerUUID)).getName();
            return name == null ? playerUUID : name;
        } catch(IllegalArgumentException e) {
            return "?";
        }
    }

    private Integer parse(CommandSender sender, String[] args, int min, int max) {
        if(args.length < 3) { send(sender, "&cInvalid Usage: " + usage(args[0].toLowerCase())); return null; }
        try {
            int value = Integer.parseInt(args[2]);
            if(value >= min && value <= max) return value;
        } catch(NumberFormatException ignored) {}
        send(sender, "&cExpected a number from " + min + " to " + max + ".");
        return null;
    }

    private static String usage(String sub) {
        return switch(sub) {
            case "create" -> "/business create <id> [display name]";
            case "delete" -> "/business delete <id> confirm";
            case "rename" -> "/business rename <id> <display name>";
            case "add" -> "/business add <id> <online player>";
            case "remove" -> "/business remove <id> <player|character name|character uuid>";
            case "members" -> "/business members <id>";
            case "setvaults" -> "/business setvaults <id> <count>";
            case "setsize" -> "/business setsize <id> <9|18|27|36|45|54>";
            case "open" -> "/business open <id> [vault number]";
            default -> "/business <list|create|delete|rename|add|remove|members|setvaults|setsize|open> ...";
        };
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(TextUtility.INSTANCE.convertToComponent(message));
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) throws IllegalArgumentException {
        if(args.length == 1)
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if(!SneakyVaults.getInstance().hasCharacters()) return List.of();
        if(args.length == 2 && !args[0].equalsIgnoreCase("create") && !args[0].equalsIgnoreCase("list"))
            return SneakyVaults.getInstance().sharedVaultManager.getBusinesses().stream().map(Business::getId)
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        if(args.length == 3) {
            String sub = args[0].toLowerCase();
            if(sub.equals("add")) return super.tabComplete(sender, alias, args);
            if(sub.equals("setsize")) return List.of("9", "18", "27", "36", "45", "54");
            if(sub.equals("delete")) return List.of("confirm");
            if(sub.equals("remove")) {
                Business business = SneakyVaults.getInstance().sharedVaultManager.getBusiness(args[1]);
                if(business != null)
                    return business.getMembers().values().stream().map(Business.Member::characterName).toList();
            }
        }
        return List.of();
    }
}
