# SneakyVaults

Paper plugin for item vaults. Needs SneakyUtils; SneakyCharacterManager is needed for character and business vaults, SneakyVariables for the vault wall, and CoreProtect (optional) logs vault clicks.

## Vault types

| Type | Command | Belongs to | Limits |
| --- | --- | --- | --- |
| Global | `/vault [n]` (`/openvault`, `/pv`, `/ov`) | The player account | `sneakyvaults.max.N`, `sneakyvaults.slots.<9..54>` |
| Character | `/cvault [n]` (`/charactervault`, `/cv`) | The character the player is currently loaded into | `sneakyvaults.character.max.N`, `sneakyvaults.character.slots.<9..54>` |
| Business | `/bvault [business] [n]` (`/bv`) | A business group; every member character shares it | Set per business with `/business setvaults` and `/business setsize` |

- Command permissions: `sneakyvaults.openvault`, `sneakyvaults.cvault`, `sneakyvaults.bvault`.
- Character and business vaults are one live inventory per vault, so several viewers see the same contents at once. They save every time someone closes them.
- A vault that is not loaded yet is read in the background and opens a tick or so after the command.
- Switching character closes any open character or business vault.
- Vaults never shrink: lowering a permission or a business's size keeps the saved size, and items in vault numbers above the limit are kept but can't be opened.

## Vault wall

The bank's wall of chests (formerly MagicSpells `spells-city-vaults.yml`), configured in `plugins/SneakyVaults/vault-wall.yml`. Right-clicking a configured block opens that player's global vault. If the vault hasn't been bought yet, a "Buy Vault?" menu offers it for the configured price, paid from a bank variable. Chests can instead require a variable at a minimum (Vault Master rank), be free, or be `existing_only`: open only for players who already have saved data in that vault, and never make a new one.

- Ships with `enabled: false` and the 31 live chests (vaults 1-8, 11-18, 21-28 bought with `bankPenny`/`bankSilver`/`bankGold`, 31-36 for Vault Master 1-6, 54 free). Turn it on only once the MagicSpells wall spells and `statTrading-vaultDiscount` are gone, or both answer the same click.
- Ownership is kept in the existing SneakyVariables player variables (`vaultPenny1`, `vaultSilver1`, ...), which SneakyVaults defines (0/1). The variables named in `requires` and `discount_variable` are defined too, so their stored values stay readable. Bank variables are not defined here; a purchase is refused while its currency is undefined or the player's variables are still loading.
- Price paid: `ceil(amount * (100 - discount) / 100)`, discount being `discount_variable` as a whole percentage (0-100).
- Opening still goes through the normal vault limits: `sneakyvaults.max.<n>` and `sneakyvaults.slots.<size>`. The wall does not need `sneakyvaults.openvault`.
- Without SneakyVariables the wall stays off; the rest of the plugin works as before.
- `/vaultwall reload|list [player]|grant <player> <chest>|reset <player> <chest|all>` (`sneakyvaults.admin.vaultwall`). `grant` and `reset` set the ownership variable of an online player; `list <player>` shows what they own and what they would pay.
- Purchases are logged to the console.

## Admin commands

All need `sneakyvaults.admin.<command>`.

- `/business list|create|delete|rename|add|remove|members|setvaults|setsize|open`. Membership is per character: `add <id> <player>` adds the character that player is loaded into. `delete <id> confirm` moves the file to `Businesses/deleted/`.
- `/peekvault <player|uuid> [n]`: open an existing global vault.
- `/peekcvault <online player|character uuid> [n]`: open an existing character vault.
- `/searchvault name|id|magicitem <text>`: search global vaults (`name`/`id` match a substring; `magicitem` matches the SneakyItems magic-item name exactly, e.g. `item-characterCrystal`, and needs SneakyItems).
- `/templates`, `/opentemplate`: preset template vaults.
- `/forcevaultconversions`: convert old global vault data to Paper serialization.

## Diagnostics

Measured by [SneakyDiagnostics](https://github.com/Team-Sneakymouse/SneakyDiagnostics) when it is installed (optional): `/diagnostics plugins start 60 SneakyVaults`.
Setup and rules: [plugin-providers.md](https://github.com/Team-Sneakymouse/SneakyDiagnostics/blob/main/docs/plugin-providers.md).
Sections, per vault kind (`player` for `/openvault`, `character`, `business`, `template` for `openTemplateVault`): `vault.<kind>.open` (the server-thread part of the command or call), `vault.<kind>.load` (server-thread work to find the vault or build its inventory from data read in the background; file reads and writes run on the `SneakyVaults-IO` thread and are not measured) and `vault.<kind>.open-inventory` (`Player.openInventory`, which includes every plugin's inventory listeners, such as SneakyItems' inventory update). `vault.player.load` also covers global vaults built for admin peeks. Other admin peek, search and edit work is not measured. No counts or levels.

## Data

In `plugins/SneakyVaults/`:

- `PlayerData/<player uuid>.yml`: global vaults (`player_vaults.<n>.items`) and players' template copies.
- `CharacterData/<character uuid>.yml`: `player`, `name`, `vaults.<n>.items`.
- `Businesses/<id>.yml`: `name`, `vault_count`, `vault_size`, `members.<character uuid>.{name,player}`, `vaults.<n>.items`.
- `config.yml`: `template_vaults`, `banned_ms_items`.
- `vault-wall.yml`: the vault wall (see above). Only read, never written by the plugin. If it is not valid YAML at startup, the plugin logs an error and saves no template changes until it is fixed, rather than replacing it.

Every file is written to a temporary file and then moved into place, so a crash never leaves a half-written file. Once a vault or template copy is loaded, its in-memory contents are the authoritative copy: it is only dropped from memory after its last close was written and nobody is viewing it, so a failed write is never replaced by the older file and is retried on shutdown. While the server runs, vault reads and writes happen one at a time, in order, on the `SneakyVaults-IO` thread rather than the server thread. Exceptions: loading businesses and `config.yml` and writing the default config happen while the plugin enables, and `/searchvault` lists and reads `PlayerData` on its own background task (read-only). On shutdown the plugin waits up to 60 seconds for queued saves to finish. A save still queued when the server process is killed (not stopped) is lost, so the vault file can briefly lag behind the player's own saved inventory; on a healthy disk that window is milliseconds.
