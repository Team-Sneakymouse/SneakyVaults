# Offline player vault access and LuckPerms 5.5.60

## Conclusion

The implemented admin workflow edits existing vault storage only. It does not create vaults, resize them, or enforce the owner's current entitlement. That means it does not need LuckPerms or Vault at all. Bukkit's cached offline-player lookup resolves known names, UUID input addresses the UUID-named data files directly, and the saved inventory determines its size.

If a future feature needs to read or change an offline player's permissions, it should use the LuckPerms 5.5 API directly. Vault's permission facade can reach an offline LuckPerms user, but it is a synchronous compatibility API that may block or throw during storage lookup and reports success before the asynchronous save has finished.

## Scope and inspected version

There was no existing Markdown notes convention in this repository, so this note lives under `docs/research`.

The supplied `C:\Users\DaniDipp\Downloads\26.2\LuckPerms-Bukkit-5.5.60.jar` identifies itself as LuckPerms 5.5.60. Its SHA-256 is `24869A07C3A6AB2966C5EC1F85238B9CB674F46437411BA056E6B5863EE17BB8`. I inspected the public API classes and the nested Bukkit implementation with `javap`; the relevant 5.5.60 bytecode agrees with the 5.5 Javadocs and current official source cited below.

## Requirements for future permission-aware features

### Dependency and service lifecycle

- Add `compileOnly("net.luckperms:api:5.5")` only if a future feature reads or writes LuckPerms data. LuckPerms documents API 5.5 as the current major-compatible API line and publishes that artifact on Maven Central. Obtain `LuckPerms` from Bukkit's `ServicesManager` after LuckPerms enables. [LuckPerms developer API](https://luckperms.net/wiki/Developer-API)
- If offline vault authorization requires LuckPerms, declare a hard `depend: [LuckPerms]` in `plugin.yml`. A `softdepend` is appropriate only if SneakyVaults has a defined fail-closed fallback. The current descriptor only lists CoreProtect.
- Do not shade the LuckPerms API into SneakyVaults. It is a provided server service.

### Loading and reading an offline user

`UserManager#getUser(UUID)` only returns a user already loaded in memory. Offline users usually are not loaded, so a null result is normal. `loadUser(UUID)` loads or creates the user from the configured storage provider and returns `CompletableFuture<User>`. [Official usage guide](https://luckperms.net/wiki/Developer-API-Usage), [UserManager 5.5 source](https://github.com/LuckPerms/LuckPerms/blob/master/api/src/main/java/net/luckperms/api/model/user/UserManager.java)

For SneakyVaults, the permission-read flow should be conceptually:

```java
userManager.loadUser(uuid).thenApply(user -> {
    var permissions = user.getCachedData().getPermissionData();
    // derive sneakyvaults.max.N and sneakyvaults.slots.N
});
```

LuckPerms' cached permission data includes inherited and platform-default data. `getPermissionMap()` returns an immutable resolved map, and `checkPermission(node)` behaves like the platform permission check. [CachedPermissionData 5.5](https://javadoc.io/static/net.luckperms/api/5.5/net/luckperms/api/cacheddata/CachedPermissionData.html)

For the numbered SneakyVaults nodes, filter out entries whose Boolean value is `false`, validate the suffix before parsing it, and bound the result to values the inventory code supports. The existing online implementation parses every matching suffix without checking its value. A negated `sneakyvaults.max.100` or a malformed node can therefore produce the wrong limit or an exception.

### Offline contexts are a product decision

Cached data is indexed by `QueryOptions`. A contextual query applies only nodes whose contexts match; a non-contextual query does not filter by context. [QueryMode 5.5](https://javadoc.io/static/net.luckperms/api/5.5/net/luckperms/api/query/QueryMode.html)

An offline user has no current world, gamemode, or location. LuckPerms can still supply static contexts such as the server context, but SneakyVaults must decide what world-specific vault permissions mean while that player is offline:

- If vault limits are intended to be global, keep these permission nodes context-free and use the user's normal cached permission data.
- If limits vary by server or world, the admin command must provide the intended server/world and construct matching contextual `QueryOptions`. Do not silently use the command sender's world.
- A non-contextual query includes contextual assignments without asking whether their contexts currently apply. It is suitable only if the intended policy is "maximum entitlement in any context," which is different from the online `Player#hasPermission` behavior.

### Modifying LuckPerms data

For a direct node change, use `UserManager#modifyUser(UUID, Consumer<User>)`. It performs load, action, and save, returning one `CompletableFuture<Void>` for the whole operation. If code instead calls `loadUser`, mutates `user.data()`, and stops, the change is not durably saved; it must call and observe `saveUser(user)`. [UserManager source and contract](https://github.com/LuckPerms/LuckPerms/blob/master/api/src/main/java/net/luckperms/api/model/user/UserManager.java), [official save/modify examples](https://luckperms.net/wiki/Developer-API-Usage)

```java
CompletableFuture<Void> result = userManager.modifyUser(uuid, user ->
    user.data().add(PermissionNode.builder(permission).value(true).build())
);
```

Use a node with the same key, value, and contexts when removing it. Decide whether the operation changes a direct assignment or only reads inherited effective permissions. Removing a direct node does not revoke a permission inherited from a group.

Handle the future's exceptional completion and only report success after it completes. The 5.5.60 implementation runs the mutation action on LuckPerms' async executor and composes the storage save afterward. Do not call Bukkit inventory, player, world, or scheduler-confined APIs inside the `modifyUser` consumer. LuckPerms itself is thread-safe, but that promise does not extend to Bukkit. [LuckPerms threading and blocking guidance](https://luckperms.net/wiki/Developer-API)

Avoid launching competing read-modify-save operations for the same UUID. `modifyUser` is a convenient asynchronous sequence, not a documented cross-process transaction or compare-and-set primitive. Chain related mutations into one call when they must remain consistent.

`cleanupUser(user)` is optional memory housekeeping for a user who is still offline. It is not a save operation and should never substitute for awaiting `saveUser` or `modifyUser`.

### UUID and name lookup

Prefer UUID input and the UUID already encoded in SneakyVaults' player-data filename. For name input, call `UserManager#lookupUniqueId(name)` asynchronously. The lookup is case-insensitive and uses LuckPerms' UUID/name cache; its result may be null. `lookupUsername(UUID)` may also return null. [UserManager lookup contract](https://github.com/LuckPerms/LuckPerms/blob/master/api/src/main/java/net/luckperms/api/model/user/UserManager.java)

Do not invent an online UUID, calculate an offline UUID, or assume the server is using one particular identity mode. LuckPerms identifies authenticated and unauthenticated UUIDs separately; unauthenticated UUIDs are usually name-derived version-3 UUIDs, while authenticated UUIDs are usually version 4. [UniqueIdDetermineTypeEvent 5.5](https://javadoc.io/static/net.luckperms/api/5.5/net/luckperms/api/event/player/lookup/UniqueIdDetermineTypeEvent.html)

On offline-mode or proxied networks, correct UUID forwarding is part of the data model. Changing forwarding or identity mode can cause the same username to map to different user records. LuckPerms' configuration documentation also notes that username lookup depends on its cache unless server-cache/Mojang lookup behavior is enabled. A never-seen name is therefore not a reliable target; require a UUID when lookup returns no result. [LuckPerms configuration](https://luckperms.net/wiki/Configuration), [current Bukkit UUID-cache setting](https://github.com/LuckPerms/LuckPerms/blob/master/bukkit/src/main/resources/config.yml)

## Why not use Vault permissions for this path

Vault's generic `Permission` API is synchronous and returns booleans. Its base `OfflinePlayer` overloads often delegate through `OfflinePlayer#getName()`, so providers that do not override them lose the UUID and can fail when the name is null. [Vault Permission source](https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/permission/Permission.java)

LuckPerms does override those overloads. In 5.5.60, `AbstractVaultPermission` passes `OfflinePlayer#getUniqueId()` into its UUID-based methods, including normal permissions, group membership, and transient nodes. [LuckPerms AbstractVaultPermission source](https://github.com/LuckPerms/LuckPerms/blob/master/bukkit/src/main/java/me/lucko/luckperms/bukkit/vault/AbstractVaultPermission.java)

That avoids Vault's generic name-loss problem, but two larger limits remain:

- When the user is not loaded, the LuckPerms Vault bridge must query storage synchronously. By default it throws `ServerThreadLookupException` if this happens on the primary thread; enabling `vault-unsafe-lookups` permits the blocking lookup and risks server lag. The bridge's own class comment calls this out. [LuckPermsVaultPermission lookup path](https://github.com/LuckPerms/LuckPerms/blob/master/bukkit/src/main/java/me/lucko/luckperms/bukkit/vault/LuckPermsVaultPermission.java)
- After mutating a user, the bridge starts `storage.saveUser` in the background and immediately returns `true`. The caller cannot await durability or receive an asynchronous save failure through Vault's boolean. [LuckPermsVaultPermission save path](https://github.com/LuckPerms/LuckPerms/blob/master/bukkit/src/main/java/me/lucko/luckperms/bukkit/vault/LuckPermsVaultPermission.java)

Vault remains useful when the permissions provider is intentionally unknown. If SneakyVaults later adds permission-aware offline work, the known LuckPerms backend makes its direct asynchronous API the better fit.

### Economy is separate

LuckPerms is the permissions backend, not an economy provider. Vault's `Economy` service is supplied by whichever economy plugin the server installs. Its offline-player methods and thread guarantees therefore cannot be inferred from LuckPerms.

Vault Economy has no `setBalance` operation. It exposes `hasAccount`, `getBalance`, `withdrawPlayer`, `depositPlayer`, and optional account creation, returning provider-specific `EconomyResponse` values for transactions. World-specific behavior is explicitly implementation-specific. [Vault Economy source](https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/Economy.java)

If "vault modification" later includes charging an offline owner, that requires a separate review of the actual economy provider. Check `hasAccount`, use the `OfflinePlayer` transaction overloads, reject negative amounts, inspect `EconomyResponse#transactionSuccess()`, and do not assume the provider is safe off-thread or that a withdraw plus vault save is atomic.

## Blockers found in the original implementation

1. `VaultManager#getMaxAllowedVaults` and `getMaxVaultSize` only work with an online Bukkit `Player`; both return `-1` when offline. [`VaultManager.java`](../../src/main/java/net/sneakymouse/sneakyvaults/managers/VaultManager.java)
2. `getPlayerVault` treats `maxVaults == -1` as unrestricted. It creates the requested vault number with 54 slots. That means `/peekvault offlineName 9999` can create a maximum-size vault even when the owner has no matching permissions.
3. The online count comparisons are inconsistent. One branch rejects only `vaultNumber > maxVaults`, while creation branches require `maxVaults > vaultNumber`; the boundary vault can behave differently depending on cache state.
4. `CommandPeekVault` uses Paper's async scheduler for `Bukkit.getOfflinePlayer`, chat, `VaultManager` access, YAML-backed `PlayerVault` construction, `Bukkit.createInventory`, and mutation of ordinary `HashMap`s. Only `openInventory` returns to the main scheduler. [`CommandPeekVault.java`](../../src/main/java/net/sneakymouse/sneakyvaults/commands/admin/CommandPeekVault.java)
5. Splitting the flow across schedulers leaves a race between permission resolution, vault creation, concurrent opens, and `vault.isOpened`. Resolve identity and LuckPerms data asynchronously, then marshal the stateful vault decision and Bukkit inventory creation/opening to the appropriate server thread or Folia region scheduler.
6. CoreProtect logging passes `PlayerVault#getDummyLocation()` into its reflective inventory logger. That method returns null whenever the vault owner is offline, which is exactly the admin-peek case. Verify CoreProtect's null contract or supply a stable non-player-based location before considering offline editing safe. [`PlayerVault.java`](../../src/main/java/net/sneakymouse/sneakyvaults/types/PlayerVault.java), [`CoreProtectLoggerEvents.java`](../../src/main/java/net/sneakymouse/sneakyvaults/events/CoreProtectLoggerEvents.java)

## Implemented boundary

The agreed admin workflow is deliberately narrower than the permission-aware design considered above:

1. Parse UUID input directly, or resolve names only through Paper's non-blocking cached offline-player lookup.
2. Require the UUID-named file and requested vault entry to exist. Never create storage from `/peekvault`.
3. Decode and validate the whole saved inventory before constructing a Bukkit inventory. Refuse malformed YAML, item data, or inventory sizes without saving anything.
4. Preserve the saved inventory size and ignore the owner's current vault entitlements for this administrative operation.
5. Perform the lookup, load, open-state transition, and Bukkit inventory work on Paper's main thread so owner and admin access share one lock.
6. Use Bukkit's first loaded world for the CoreProtect synthetic location, regardless of whether the owner is online.
