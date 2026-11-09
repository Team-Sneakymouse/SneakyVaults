# SneakyVaults

Paper plugin for item vaults. Needs SneakyUtils; SneakyCharacterManager is needed for character and business vaults, and CoreProtect (optional) logs vault clicks.

## Vault types

| Type | Command | Belongs to | Limits |
| --- | --- | --- | --- |
| Global | `/vault [n]` (`/openvault`, `/pv`, `/ov`) | The player account | `sneakyvaults.max.N`, `sneakyvaults.slots.<9..54>` |
| Character | `/cvault [n]` (`/charactervault`, `/cv`) | The character the player is currently loaded into | `sneakyvaults.character.max.N`, `sneakyvaults.character.slots.<9..54>` |
| Business | `/bvault [business] [n]` (`/bv`) | A business group; every member character shares it | Set per business with `/business setvaults` and `/business setsize` |

- Command permissions: `sneakyvaults.openvault`, `sneakyvaults.cvault`, `sneakyvaults.bvault`.
- Character and business vaults are one live inventory per vault, so several viewers see the same contents at once. They save every time someone closes them.
- Switching character closes any open character or business vault.
- Vaults never shrink: lowering a permission or a business's size keeps the saved size, and items in vault numbers above the limit are kept but can't be opened.

## Admin commands

All need `sneakyvaults.admin.<command>`.

- `/business list|create|delete|rename|add|remove|members|setvaults|setsize|open`. Membership is per character: `add <id> <player>` adds the character that player is loaded into. `delete <id> confirm` moves the file to `Businesses/deleted/`.
- `/peekvault <player|uuid> [n]`: open an existing global vault.
- `/peekcvault <online player|character uuid> [n]`: open an existing character vault.
- `/searchvault name|id <text>`: search global vaults.
- `/templates`, `/opentemplate`: preset template vaults.
- `/forcevaultconversions`: convert old global vault data to Paper serialization.

## Diagnostics

Measured by [SneakyDiagnostics](https://github.com/Team-Sneakymouse/SneakyDiagnostics) when it is installed (optional): `/diagnostics plugins start 60 SneakyVaults`.
Setup and rules: [plugin-providers.md](https://github.com/Team-Sneakymouse/SneakyDiagnostics/blob/main/docs/plugin-providers.md).
Sections, per vault kind (`player` for `/openvault`, `character`, `business`, `template` for `openTemplateVault`): `vault.<kind>.open` (the whole command or call), with `vault.<kind>.load` (finding or reading the vault and building its inventory) and `vault.<kind>.open-inventory` (`Player.openInventory`, which includes every plugin's inventory listeners, such as SneakyItems' inventory update). Admin peek, search and edit commands are not measured. No counts or levels.

## Data

In `plugins/SneakyVaults/`:

- `PlayerData/<player uuid>.yml`: global vaults (`player_vaults.<n>.items`) and players' template copies.
- `CharacterData/<character uuid>.yml`: `player`, `name`, `vaults.<n>.items`.
- `Businesses/<id>.yml`: `name`, `vault_count`, `vault_size`, `members.<character uuid>.{name,player}`, `vaults.<n>.items`.
- `config.yml`: `template_vaults`, `banned_ms_items`.

Every file is written to a temporary file and then moved into place, so a crash never leaves a half-written file.
