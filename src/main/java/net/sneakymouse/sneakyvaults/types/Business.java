package net.sneakymouse.sneakyvaults.types;

import net.sneakymouse.sneakyvaults.SneakyVaults;
import net.sneakymouse.sneakyvaults.persistence.AtomicYamlFiles;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * A business group. Its member characters share its vaults.
 * Stored in {@code Businesses/<id>.yml} next to the vault contents.
 * The in-memory copy is authoritative; {@link #save} writes it in the background.
 */
public class Business {

    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");

    public record Member(String characterName, String playerUUID) {}

    private final String id;
    private final File file;
    private String name;
    private int vaultCount = 1;
    private int vaultSize = 27;
    private final Map<String, Member> members = new LinkedHashMap<>();

    public Business(String id, String name, File file) {
        this.id = id;
        this.name = name;
        this.file = file;
    }

    /** Reads the file on the calling thread; only used while the plugin enables. */
    public static Business load(File file) throws IOException {
        String id = file.getName().substring(0, file.getName().length() - ".yml".length());
        YamlConfiguration configuration = AtomicYamlFiles.load(file);

        Business business = new Business(id, configuration.getString("name", id), file);
        business.vaultCount = configuration.getInt("vault_count", 1);
        business.vaultSize = configuration.getInt("vault_size", 27);

        ConfigurationSection memberSection = configuration.getConfigurationSection("members");
        if(memberSection != null) {
            for(String characterUUID : memberSection.getKeys(false)) {
                business.members.put(characterUUID, new Member(
                        memberSection.getString(characterUUID + ".name", "?"),
                        memberSection.getString(characterUUID + ".player", "")
                ));
            }
        }
        return business;
    }

    /**
     * Server thread: snapshot the settings and members and queue writing them.
     * Vault contents in the same file are left alone.
     * @return Completes when written, or exceptionally if the write failed.
     */
    public CompletableFuture<Void> save() {
        String savedName = name;
        int savedCount = vaultCount;
        int savedSize = vaultSize;
        Map<String, Member> savedMembers = new LinkedHashMap<>(members);
        return SneakyVaults.getInstance().persistence.run(() -> AtomicYamlFiles.update(file, configuration -> {
            configuration.set("name", savedName);
            configuration.set("vault_count", savedCount);
            configuration.set("vault_size", savedSize);
            configuration.set("members", null);
            savedMembers.forEach((characterUUID, member) -> {
                configuration.set("members." + characterUUID + ".name", member.characterName());
                configuration.set("members." + characterUUID + ".player", member.playerUUID());
            });
        }));
    }

    public String getId() { return id; }
    public File getFile() { return file; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getVaultCount() { return vaultCount; }
    public void setVaultCount(int vaultCount) { this.vaultCount = vaultCount; }
    public int getVaultSize() { return vaultSize; }
    public void setVaultSize(int vaultSize) { this.vaultSize = vaultSize; }
    public Map<String, Member> getMembers() { return members; }
    public boolean isMember(String characterUUID) { return members.containsKey(characterUUID); }
}
