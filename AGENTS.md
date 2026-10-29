# Agent instructions (SneakyVaults)

Follow the Team-Sneakymouse cross-repo rules in EmptyPlugin's AGENTS.md; this file adds rules specific to this repo.

## Repo-specific rules

- **Write vault files only via `AtomicYamlFiles`.** Don't call `YamlConfiguration.save(File)` or `File.delete` on `PlayerData/`, `CharacterData/` or `Businesses/` files. Use `persistence/AtomicYamlFiles` (and `SharedVault.updateFile`, which `Business.save()` uses) so a crash can't leave a half-written inventory.
- **Never shrink or drop stored vault slots.** When permissions or business size drop, keep the saved size and contents and only block opening. Don't trim items on load or save.
- **Character and business vaults are keyed by the character id**, not the player UUID. A new vault type must resolve ownership through the SneakyCharacterManager `LoadCharacterEvent` integration and close open shared vaults on character switch.
- **Template vaults live in `config.yml`**, saved through `SneakyVaults.saveConfig()`. Don't rewrite `config.yml` wholesale or save a stale in-memory copy.
- **Keep disk IO off hot paths.** New search or conversion commands follow `CommandSearchVault`: scan files in `runTaskAsynchronously` and hand results back with `runTask`.
- **Business deletion archives, never deletes.** Deleting a business moves its yml to `Businesses/deleted/<id>-<ms>.yml` (`SharedVaultManager`); keep that when touching `CommandBusiness`.
