# Magic Carpet — Implementation Plan

Executes the design at `docs/superpowers/specs/2026-07-21-magic-carpet-design.md`.
Ten tasks, ordered so each depends only on interfaces earlier tasks established.

## Global Constraints

Binding on every task. A subagent that violates any of these has failed the task.

- **Java 25.** Paper **26.1.2 build 74** (`io.papermc.paper:paper-api:26.1.2.build.74-stable`, `provided`).
- **`api-version: '26.1'`** in `plugin.yml`. Already set. Do not change it. `'26.2'` would be rejected — `26.1.2` is the accepted ceiling.
- **Maven group `org.xpfarm`, artifact `magic-carpet`. Package root `org.xpfarm.magiccarpet`.**
- **Pure Bukkit/Paper API only. NO NMS, no reflection into server internals.** Paper 26.1 removed the internal remapper; NMS against Spigot mappings is dead. If a task seems to need NMS, it is BLOCKED — report, do not improvise.
- **AGPL-3.0-or-later header on every new `.java` file**, in the exact form used by `src/main/java/org/xpfarm/magiccarpet/MagicCarpetPlugin.java`. Copy that header and change only the first descriptive line.
- **Never call `setVelocity()`** on the carpet mount or either visual entity. Falling-block velocity does not move on Bedrock (Geyser #5655, "Can't Fix") and velocity on a mount flings Bedrock riders off (Geyser #6454). Movement is `teleport(loc, TeleportFlag.EntityState.RETAIN_PASSENGERS)`, always.
- **Geyser/Floodgate/ViaVersion safety.** No Java-only chat-input prompts; all player interaction is commands, held items, and movement input, which Geyser translates. Floodgate is a *soft* dependency accessed reflectively or via `Bukkit.getPluginManager().isPluginEnabled("floodgate")` guarding — the plugin must load and work with Floodgate absent, treating every player as Java. Never hard-import a Floodgate class in a path that executes when Floodgate is absent.
- **No external services.** This plugin makes no outbound network calls of any kind. If a task seems to need one, it is BLOCKED.
- **Tests are written with the code, not after.** Any logic separable from the Bukkit runtime — config parsing and validation, fuel arithmetic, altitude/world/combat predicates, command argument parsing — gets JUnit 5 tests in `src/test/java/org/xpfarm/magiccarpet/`. Do not write tests that assert nothing, and do not use a mock framework: none is on the classpath. Design for testability with plain interfaces and hand-written fakes.
- **Startup never throws.** A malformed config value logs a warning and falls back to its default. `onEnable()` must not propagate an exception for any config input.
- **Verification command:** `mvn --batch-mode --no-transfer-progress clean verify`. It must be green before you report DONE. `mvn` and `java` are not on PATH — export first:
  ```bash
  export JAVA_HOME=~/.sdkman/candidates/java/current
  export PATH="$JAVA_HOME/bin:$HOME/.sdkman/candidates/maven/current/bin:$PATH"
  ```

## Task 1: Configuration layer

Create the typed, validated configuration model.

**Files:** `config/MagicCarpetConfig.java`, `config/ConfigSource.java`, `config/BukkitConfigSource.java`, `src/main/resources/config.yml`, `src/test/java/org/xpfarm/magiccarpet/config/MagicCarpetConfigTest.java`

`ConfigSource` is a narrow interface over key lookup — `getString(path, def)`, `getInt(path, def)`, `getDouble(path, def)`, `getBoolean(path, def)`, `getStringList(path)` — so config parsing is testable without a Bukkit server. `BukkitConfigSource` adapts `org.bukkit.configuration.file.FileConfiguration`. Tests use a hand-written in-memory `ConfigSource` fake.

`MagicCarpetConfig` is an immutable record (or final class with getters) built by a static `load(ConfigSource, Consumer<String> warn)` factory. Keys, types, defaults, and valid ranges:

| Key | Type | Default | Valid range / values |
| --- | --- | --- | --- |
| `flight.java-mode` | `FlightModeKind` | `SEATED` | `seated`, `standing` (case-insensitive) |
| `flight.bedrock-mode` | `FlightModeKind` | `STANDING` | `seated`, `standing` (case-insensitive) |
| `flight.speed` | double | `0.5` | `0.05`–`2.0` blocks/tick |
| `flight.altitude-ceiling` | int | `64` | `8`–`320` blocks above terrain |
| `fuel.capacity-seconds` | int | `60` | `5`–`3600` |
| `fuel.recharge-seconds` | int | `120` | `5`–`3600` |
| `combat.drop-on-damage` | boolean | `true` | — |
| `combat.grace-ticks` | int | `40` | `0`–`1200` |
| `worlds.mode` | `WorldListMode` | `DENY_LIST` | `allow-list`, `deny-list` |
| `worlds.list` | `List<String>` | empty | — |
| `worldguard.respect-regions` | boolean | `true` | — |
| `effects.particles` | boolean | `true` | — |
| `effects.sound` | boolean | `true` | — |

`FlightModeKind` and `WorldListMode` are enums in the `config` package.

**Validation rule, applied uniformly:** a value outside its range, or an unparseable enum, emits exactly one warning through the `warn` consumer naming the key, the offending value, and the default being substituted — then uses the default. Never throw.

**Tests must cover:** every default when the source is empty; a valid override for each key; an out-of-range numeric for at least `flight.speed`, `flight.altitude-ceiling`, and `fuel.capacity-seconds` (falls back, warns once); an unparseable enum for `flight.java-mode` and `worlds.mode`; case-insensitive enum parsing; that the warn consumer receives one message per invalid key and zero messages for a fully valid source.

Also write `src/main/resources/config.yml` with every key at its default and a brief comment per section. It must parse to exactly the defaults above.

## Task 2: Fuel tank

Pure fuel arithmetic, no Bukkit types.

**Files:** `flight/FuelTank.java`, `src/test/java/org/xpfarm/magiccarpet/flight/FuelTankTest.java`

Models one player's charge as a tick-driven state machine. Constructor takes `capacityTicks` and `rechargeTicks` (the config's seconds × 20, converted by the caller). Internal charge is a double in ticks, clamped to `[0, capacityTicks]`, starting full.

API:
- `void drain(int ticks)` — airborne; subtracts `ticks`, clamped at 0.
- `void recharge(int ticks)` — grounded; adds `capacityTicks / (double) rechargeTicks * ticks`, clamped at capacity. So a full refill takes `rechargeTicks` regardless of capacity.
- `boolean isEmpty()` — charge at or below 0.
- `double fraction()` — `charge / capacityTicks`, in `[0, 1]`, for the HUD/gauge.
- `void refill()` — set to full.

**Tests must cover:** starts full; draining below zero clamps to empty and `isEmpty()` is true; recharging past capacity clamps and `fraction()` is exactly 1.0; a full drain then exactly `rechargeTicks` of recharge returns to full (within a small epsilon); `fraction()` is monotonic and stays within `[0,1]` across an interleaved drain/recharge sequence; `refill()` from empty gives full. Guard against a zero or negative `rechargeTicks` in the constructor by treating it as 1 rather than dividing by zero.

## Task 3: Flight guard

The predicates deciding whether flight may start or must stop. No Bukkit types in the decision logic.

**Files:** `flight/FlightGuard.java`, `flight/RegionQuery.java`, `flight/DenyReason.java`, `src/test/java/org/xpfarm/magiccarpet/flight/FlightGuardTest.java`

`RegionQuery` is a one-method interface — `boolean flightAllowed(String worldName, int x, int y, int z)` — so WorldGuard stays out of the tested logic. Task 9 supplies a real implementation; a permissive default (`(w,x,y,z) -> true`) is used when WorldGuard is absent or `worldguard.respect-regions` is false.

`DenyReason` is an enum: `WORLD_DISABLED`, `REGION_DENIED`, `NO_PERMISSION`. Each carries a default player-facing message string.

`FlightGuard` takes the config and a `RegionQuery`. Methods:
- `Optional<DenyReason> checkDeploy(String worldName, int x, int y, int z, boolean hasUsePermission)` — empty means allowed. Order: permission, then world list, then region.
- `boolean worldAllowed(String worldName)` — honours `worlds.mode`: in `ALLOW_LIST` only listed worlds pass; in `DENY_LIST` listed worlds fail. World name comparison is case-insensitive.
- `int clampAltitude(int currentY, int groundY)` — returns the highest permitted Y given `flight.altitude-ceiling` blocks above `groundY`; never returns below `groundY`.

**Tests must cover:** allow-list with the world listed and not listed; deny-list with the world listed and not listed; case-insensitive world matching; empty list under both modes; missing permission short-circuits before the world check; region denial when the query returns false; the permissive query allowing everything; `clampAltitude` at, below, and above the ceiling, and the degenerate case where `currentY < groundY`.

## Task 4: Carpet item

The rug item and its recipe.

**Files:** `item/CarpetItem.java`, `src/test/java/org/xpfarm/magiccarpet/item/CarpetItemTest.java`

- A `NamespacedKey` marker (`magiccarpet:carpet`) written into the item's `PersistentDataContainer` as `PersistentDataType.BYTE` value `1`.
- `ItemStack create()` — builds the rug: a carpet `Material`, a display name, lore describing the controls, and the enchantment glint. Prefer `ItemMeta#setEnchantmentGlintOverride(true)` over adding a real enchantment.
- `boolean isCarpet(ItemStack)` — null-safe, meta-safe, returns true only when the marker is present. Must not throw on a null stack, an air stack, or a stack with no meta.
- `NamespacedKey recipeKey()` and `ShapedRecipe recipe()` — a shaped recipe producing one rug. Use a plain, readable recipe: three wool/carpet blocks across the middle row with a phantom membrane above the centre and a gold ingot below it. Choose concrete `Material` constants that exist in the 26.1.2 API; if a chosen constant does not resolve, pick the nearest one that does rather than reporting BLOCKED.

**Testing note:** `ItemStack`/`ItemMeta` construction requires a running Bukkit server, so `create()` cannot be unit tested here. Test only what is server-independent: that `isCarpet(null)` is false, and extract the marker-key construction and any pure predicate into a testable static so the key's namespace and name are asserted. Do not fabricate a Bukkit mock. State plainly in your report which methods are untestable without the runtime, so the controller can record them as gate 7a obligations.

## Task 5: Carpet visual

The dual per-edition visual, attached as passengers.

**Files:** `visual/CarpetVisual.java`, `visual/EditionResolver.java`

`EditionResolver` — one method `boolean isBedrock(Player)`. The real implementation checks `Bukkit.getPluginManager().isPluginEnabled("floodgate")` **once at construction**, and only then calls Floodgate. Access Floodgate reflectively (`Class.forName("org.geysermc.floodgate.api.FloodgateApi")`) so the class is never linked when the plugin is absent. Any failure resolves to `false` (treat as Java). Provide a static `alwaysJava()` factory for use when Floodgate is missing.

`CarpetVisual` spawns and owns both visuals for one session:
- **Java viewers** — a `BlockDisplay` with a carpet `BlockData`. Set a `Transformation` with scale `(N, 1, N)` where N comes from a constant `CARPET_SCALE = 3.0f`, and translation `(-0.5f, -0.6f, -0.5f)`. Leave `displayWidth`/`displayHeight` at `0`. Set an explicit `Brightness` so it does not darken at night, and `Billboard.FIXED`.
- **Bedrock viewers** — a `FallingBlock` with `setGravity(false)`, `setDropItem(false)`, `setHurtEntities(false)`, and `shouldAutoExpire(false)` if that method resolves on this API.

Both are spawned with `setVisibleByDefault(false)` and `setPersistent(false)`, tagged with the scoreboard tag `magiccarpet`, then added as passengers of the mount entity the caller supplies. Per-viewer visibility is applied by `refreshViewers(Collection<Player>)`, which calls `Player#showEntity(plugin, entity)` for the visual matching each viewer's edition and `hideEntity` for the other. `remove()` despawns both and must be idempotent.

The `-0.6f` translation is not arbitrary: it corrects the passenger attachment offset documented in design §4. Leave a comment saying so, with the value stated as needing in-game calibration.

No unit tests — every method needs a live server. Say so explicitly in your report.

## Task 6: Flight modes

The two movement strategies behind one interface.

**Files:** `flight/FlightMode.java`, `flight/SeatedFlightMode.java`, `flight/StandingFlightMode.java`

`FlightMode` interface:
- `Entity deploy(Player player, Location at)` — starts flight, returns the mount entity for `SeatedFlightMode` or `null` for `StandingFlightMode`.
- `void tick(Player player, Input input, MagicCarpetConfig config)` — one movement step.
- `void dismiss(Player player)` — stops flight and restores normal player state.

`SeatedFlightMode.deploy` spawns the mount: an `ArmorStand` with `setInvisible(true)`, `setMarker(true)`, `setSmall(true)`, `setGravity(false)`, `setBasePlate(false)`, `setInvulnerable(true)`, `setCollidable(false)`, `setPersistent(false)`, no equipment, no custom name, and the scoreboard tag `magiccarpet`. Then `stand.addPassenger(player)`.

`SeatedFlightMode.tick` computes the next location from the player's look direction and `config.speed()`, then moves the stand with `stand.teleport(next, TeleportFlag.EntityState.RETAIN_PASSENGERS)`. Forward motion is continuous — the carpet always cruises. `Input#isSneak()` applies a downward component; `Input#isJump()` an upward one. `setVelocity` is forbidden.

`StandingFlightMode.deploy` records the player's prior `getAllowFlight()`/`isFlying()` values, then sets `setAllowFlight(true)` and `setFlying(true)`. `tick` is a no-op beyond enforcing the altitude ceiling — the client flies itself. `dismiss` restores the recorded prior values rather than assuming `false`, so it does not strip flight from a creative-mode player.

Both `dismiss` implementations must be idempotent and safe to call for a player who is offline or already dismounted.

No unit tests — both need a live server. Say so in your report.

## Task 7: Session and manager

Per-player state and the tick loop that drives everything.

**Files:** `session/CarpetSession.java`, `session/CarpetManager.java`

`CarpetSession` holds one rider's state: the `Player` UUID, the chosen `FlightMode`, the mount `Entity` (nullable), the `CarpetVisual`, the `FuelTank`, and the ground Y recorded at deploy (for the altitude ceiling).

`CarpetManager` owns a `Map<UUID, CarpetSession>` and the repeating task:
- `boolean deploy(Player)` — runs `FlightGuard.checkDeploy`; on denial sends the `DenyReason` message and returns false. On success picks the `FlightMode` from the player's edition and the config, spawns the visual, registers the session, plays the deploy sound/particles if enabled.
- `void dismiss(Player, DismissCause)` — tears down visual and mount, restores player state, returns the rug to the off-hand, plays the stow effects. `DismissCause` is an enum: `LANDED`, `FUEL_EMPTY`, `COMBAT`, `COMMAND`, `QUIT`, `DEATH`, `SHUTDOWN`. Idempotent.
- `void tick()` — every tick, for each session: poll `player.getCurrentInput()`, delegate to `FlightMode.tick`, drain fuel, enforce the altitude ceiling, and check for ground contact. **Ground contact dismisses with `LANDED`. Empty fuel dismisses with `FUEL_EMPTY` and must leave the player falling — do not cancel or mitigate fall damage in that path.**
- `void shutdownAll()` — dismisses every session with `SHUTDOWN`; called from `onDisable()`.
- `int sweepOrphans()` — on enable, scans loaded worlds for entities carrying the `magiccarpet` scoreboard tag and removes them, returning the count. This is what catches entities left by a crash.

Register the repeating task at 1-tick period. Guard the whole tick body so one player's exception cannot kill the task or affect other riders — catch, log once with the player's name, and dismiss that session.

Fuel recharge for grounded players who are *not* flying is also this class's job: keep a `FuelTank` per player beyond the session lifetime, in a separate map keyed by UUID, so charge persists between flights within a single server run. Clear it on quit.

No unit tests — all Bukkit runtime. Say so in your report.

## Task 8: Command

`/carpet` with three subcommands.

**Files:** `command/CarpetCommand.java`, `command/CarpetCommandParser.java`, `src/test/java/org/xpfarm/magiccarpet/command/CarpetCommandParserTest.java`

Split parsing from execution so parsing is testable. `CarpetCommandParser` turns `String[] args` into a `ParsedCommand` record — a `Subcommand` enum (`GIVE`, `OFF`, `RELOAD`, `HELP`, `UNKNOWN`) plus an optional target player name — with no Bukkit types. `CarpetCommand implements CommandExecutor, TabCompleter` and performs the Bukkit-side work.

Behaviour:
- `/carpet` with no args → help.
- `/carpet give` → requires `magiccarpet.admin`; with no name targets the sender (console sender with no name is an error), with a name targets that player.
- `/carpet off` → dismisses the sender's own carpet; no permission node beyond being the rider. A sender with no active carpet gets a clear message, not an error.
- `/carpet reload` → requires `magiccarpet.admin`; reloads only this plugin's own `config.yml`. **Never call `Bukkit.reload()` or any server-wide plugin reload.**
- Unknown subcommand → help, not a stack trace.
- Tab completion offers only subcommands the sender has permission for, and player names for `give`.

**Tests must cover** (parser only): empty args → `HELP`; each valid subcommand, case-insensitively; `give` with and without a target name; unknown subcommand → `UNKNOWN`; extra trailing arguments are ignored rather than causing a failure; null-safety on an empty array.

## Task 9: Listeners

**Files:** `listener/CarpetListeners.java`, `listener/WorldGuardRegionQuery.java`

One `Listener` class registering:
- `PlayerJumpEvent` (`com.destroystokyo.paper.event.player.PlayerJumpEvent`) — if the player holds a carpet in the **off-hand** (`getInventory().getItemInOffHand()`), has `magiccarpet.use`, and has no active session, call `CarpetManager.deploy`.
- `EntityDismountEvent` — if the dismounting entity is a player with an active session and the dismount was not plugin-initiated, cancel it. `CarpetManager` sets a flag around its own teardown so its dismounts pass through.
- `EntityDamageEvent` — when the damaged entity is a rider and `combat.drop-on-damage` is true, dismiss with `COMBAT`.
- `PlayerQuitEvent` → dismiss `QUIT` and clear the persisted fuel tank. `PlayerDeathEvent` → dismiss `DEATH`.
- `ChunkUnloadEvent` — dismiss any session whose mount is in the unloading chunk.
- `PrepareItemCraftEvent` — if the recipe is the carpet recipe and the viewer lacks `magiccarpet.craft`, clear the result.

`WorldGuardRegionQuery implements RegionQuery`, resolving WorldGuard reflectively so the class never links when WorldGuard is absent. Construction failure yields the permissive query. It must not be constructed at all when `worldguard.respect-regions` is false.

No unit tests — all Bukkit events. Say so in your report.

## Task 10: Plugin wiring

**Files:** `MagicCarpetPlugin.java` (rewrite the scaffold)

`onEnable()` in order: `saveDefaultConfig()`; load `MagicCarpetConfig` through `BukkitConfigSource`, routing warnings to the plugin logger; build the `EditionResolver`; build the `RegionQuery` (WorldGuard-backed only when enabled and present, permissive otherwise); construct `CarpetItem` and register the recipe; construct `FlightGuard`, `CarpetManager`; register listeners; wire `/carpet` executor and tab completer, guarding against a null `getCommand("carpet")`; run `sweepOrphans()` and log the count; start the 1-tick task.

`onDisable()`: `CarpetManager.shutdownAll()`, then cancel tasks. Must not throw even if `onEnable` failed partway.

**Startup never throws.** Wrap the enable body so an unexpected exception logs at SEVERE and disables the plugin cleanly rather than propagating. A reload path (`/carpet reload`) re-reads config and re-applies it to the manager without restarting the task or dropping active sessions.

Verify the whole thing with `mvn --batch-mode --no-transfer-progress clean verify`, then confirm the shaded JAR's embedded `plugin.yml` still declares `api-version: '26.1'`, main class `org.xpfarm.magiccarpet.MagicCarpetPlugin`, the `carpet` command, and all three permission nodes.
