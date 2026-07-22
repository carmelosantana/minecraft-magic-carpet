# New or Edited Plugin Checklist

Copy this file for one plugin and replace every `<...>` field. Leave an unchecked box with a short explanation when a gate is not complete; do not silently remove inapplicable checks.

- Plugin name: `Magic Carpet`
- Slug: `magic-carpet`
- Repository: `carmelosantana/minecraft-magic-carpet`
- Owner: `Carmelo Santana`
- Target version: `0.1.0`
- Paper version: `26.1.2 build 74`
- Java version: `25`
- Updater destination: `magic-carpet.jar`
- External services: `none`
- Status: `active`
- Autonomy: `autonomous`

Maven coordinates: `org.xpfarm:magic-carpet`. Package: `org.xpfarm.magiccarpet`.
`plugin.yml` name: `MagicCarpet`. Releasable JAR: `magic-carpet-<version>.jar`.
Command: `/carpet` (alias `/magiccarpet`).

**`api-version` is `'26.1'`.** This was a deliberate deviation when written; the
toolkit template was changed to `'26.1'` on `2026-07-21`, so it is now simply the
standard. See `PLUGIN_LIFECYCLE.md` §4 and the design doc §9.

Full design: [docs/superpowers/specs/2026-07-21-magic-carpet-design.md](superpowers/specs/2026-07-21-magic-carpet-design.md)

## 1. Scope

- [x] Status is explicitly recorded as active, experimental, or excluded.
- [x] Purpose, commands, events, permissions, configuration, persistence, and acceptance checks are defined.
- [x] Known limitations and any intentionally withheld gates are recorded.

**Player-facing purpose.** An enchanted rug carried in the off-hand. Jump while
holding it and a carpet materialises underfoot; the player flies where they look
until they sneak back to the ground, where the rug returns to their hand. Other
players see them riding it.

**Commands.**

| Command | Arguments | Who |
| --- | --- | --- |
| `/carpet give` | `[player]` | `magiccarpet.admin` |
| `/carpet off` | — | any holder mid-flight |
| `/carpet reload` | — | `magiccarpet.admin` |

Alias `/magiccarpet`.

**Events.**

| Event | Why |
| --- | --- |
| `com.destroystokyo.paper.event.player.PlayerJumpEvent` | deploy trigger. Fires on the ground→airborne+upward transition and never while riding, so it cannot double as in-flight ascent |
| `EntityDismountEvent` | cancel dismounts the plugin did not initiate |
| `EntityDamageEvent` | combat forces a descent |
| `PlayerQuitEvent`, `PlayerDeathEvent` | teardown, no orphaned entities |
| `ChunkUnloadEvent`, `PluginDisableEvent` | teardown, no orphaned entities |
| `PrepareItemCraftEvent` | enforce the craft permission |

Steering polls `Player#getCurrentInput()` on a repeating task. `PlayerInputEvent`
is **not** used as a tick source — Paper guards it with a `getLastClientInput()`
comparison, so it is edge-triggered and fires only on change.

**Permissions.**

| Node | Default | Gates |
| --- | --- | --- |
| `magiccarpet.use` | `true` | deploying a carpet |
| `magiccarpet.craft` | `true` | crafting the rug |
| `magiccarpet.admin` | `op` | `/carpet give`, `/carpet reload` |

**Configuration.** `flight.java-mode` and `flight.bedrock-mode` (`seated` |
`standing`, defaults `seated` and `standing`), `flight.speed` (double),
`flight.altitude-ceiling` (int, blocks above terrain), `fuel.capacity-seconds`
and `fuel.recharge-seconds` (int), `combat.drop-on-damage` (bool) and
`combat.grace-ticks` (int), `worlds.mode` (`allow-list` | `deny-list`) and
`worlds.list`, `worldguard.respect-regions` (bool), `effects.particles` and
`effects.sound` (bool). Numeric keys are range-validated on load; an invalid
value logs a warning and falls back to the default rather than failing enable.

**Persistence.** A PDC marker key on the item identifies a rug. Sessions, fuel
and spawned entities are in-memory only and deliberately not persisted — a
restart lands every rider rather than restoring them mid-air. No database, no
flat file beyond `config.yml`.

**Dependencies.** No hard dependencies. Soft: WorldGuard (region checks),
Floodgate (edition detection). Both absent-tolerant; without Floodgate every
player is treated as Java.

**External integrations.** `none`.

**Acceptance checks.** See design doc §10 (15 numbered checks). They cover the
craft and give paths, deploy-on-jump, the seated passenger relationship, the
per-edition dual visual, steering, sneak-to-land and stow, fuel drain and
recharge, mid-air fuel exhaustion with fall damage, the altitude ceiling, the
combat drop, world and region denial, orphan-free teardown across quit/death/
chunk-unload/disable, `/carpet reload`, a full Bedrock standing-mode flight
cycle, and invalid-config fallback.

**Known limitations.**

Upstream, not fixable here:

- Geyser [#3810](https://github.com/GeyserMC/Geyser/issues/3810) (open since 2023-06-03) — Block/Item Displays are never spawned for Bedrock. This is why the carpet needs two visuals: `BlockDisplay` for Java viewers, `FallingBlock` for Floodgate viewers.
- Geyser [#6454](https://github.com/GeyserMC/Geyser/issues/6454) / [#5911](https://github.com/GeyserMC/Geyser/issues/5911) (both open, untriaged) — a Bedrock rider's camera may not update on a server-moved mount. **This is why `flight.bedrock-mode` defaults to `standing`.**
- Geyser [#5017](https://github.com/GeyserMC/Geyser/issues/5017) (open, confirmed, untouched ~22 months) — seated players float for Bedrock viewers.
- Geyser [#6551](https://github.com/GeyserMC/Geyser/issues/6551) (open, 2026-07-16) — non-living entities are not interpolated, which constrains the vehicle to ArmorStand rather than `Interaction` or a display entity.
- Paper [#11694](https://github.com/PaperMC/Paper/issues/11694) (open; fix PR #11695 closed unmerged) — periodic position resync breaks display interpolation. Avoided by attaching visuals as passengers rather than teleporting them per tick.
- The Bedrock carpet sits ≈1 block off the Java visual, from Geyser's hardcoded `+0.995f` falling-block mount offset. Not server-controllable; both offsets need independent in-game calibration at gate 7a.
- Bedrock gamepad and touch-crosshair input arrive as a thresholded analogue vector, so diagonal steering feel differs from mouse input. Test separately.

Architectural, accepted:

- A seated rider **cannot** control their own flight. `Player.travel()` short-circuits before flight abilities when `isPassenger()` is true, so the server must drive the vehicle. This is why the flight model is "flies where you look" rather than free creative-style flight.
- `seated` mode carries ~1 RTT plus ~150 ms client interpolation. Inherent to server-driven movement.

Out of scope for v1:

- Passengers (a second rider) — deferred to v2.
- Resource pack — explicitly declined by the owner. Recorded because it would flip the design: a scale-0.25 Happy Ghast with transparent body and harness textures gives a seated rider with true client-authoritative 3-axis flight on both editions and no server movement code.

Ecosystem items needing an owner (not blockers for this plugin's gates 2–6, but
blockers for a meaningful gate 7a Bedrock test):

- ~~**Geyser version mismatch.**~~ **Not a break — resolved `2026-07-21`, no action taken.** The protocol gap is real (Geyser 2.11.0-b1200's codec is `protocolVersion(776).minecraftVersion("26.2")`; Paper 26.1.2 is 775) but it does not break Bedrock, and **neither remediation was applied**. ViaVersion is Geyser's own designed mechanism for this: `GeyserSpigotVersionChecker.checkForSupportedProtocol` delegates entirely to ViaVersion when present and never compares protocols itself, and Geyser's own message tells you to *"install ViaVersion"* when it is absent. ViaVersion 5.11.0 registers both `775 -> "26.1-26.1.2"` and `776 -> "26.2"`, so the check passes; `use-direct-connection: true` also means no Java handshake is negotiated over a socket. Two stacks booted clean on this combination with zero exceptions and none of Geyser's ViaVersion warnings, and production answers a Bedrock RakNet ping. Three claims in the original note were wrong: 2.11.0 explicitly declares 26.1.1/26.1.2; `getJavaVersions()` is `List.of(codec.getMinecraftVersion(), "26.1.1", "26.1.2")` — a dynamic first element plus a maintained down-level list, not a stale string; and the `26.x` values in `GameProtocol` are **Bedrock** versions (codecs `v924`–`v1001`), which is likely what was misread as a Java 26.2. Full write-up in the toolkit's `CURRENT_STATE.md`. **Gate 7a Bedrock testing is unblocked.**
- ~~**Toolkit template `api-version`.**~~ **Resolved `2026-07-21`** — the template, `PLUGIN_LIFECYCLE.md` §4, and the `ecosystem`/`dev`/`scaffold` skills now specify `'26.1'`. Two details in the original note were wrong when checked against Paper 26.1.2 build 74 directly: the accepted upper bound is `26.1.2` (`apiVersioning.json`'s `currentApiVersion`), not `26.2`, so `'26.2'` would be *rejected*; and there is **no** `Slime`→`AbstractCubeMob` rewrite on this build — no such type exists in the API, `Commodore` has no `Slime` entry, and `MagmaCube extends Slime` still holds. The `Cow`→`AbstractCow` rewrite is real, gated at `ApiVersion.ABSTRACT_COW` = `1.21.5`.

### Post-implementation limitations (added 2026-07-21 after the whole-branch review)

**Accepted in code — deliberately not fixed now:**

- **Collision is destination-only, so high speeds tunnel.** `SeatedFlightMode.isBlocked` checks one block at the destination. At the config maximum `flight.speed: 2.0` the mount steps 2 blocks per tick and passes through any wall ≤1 block thick. Sound at the 0.5 default; the guarantee weakens as `flight.speed` rises.
- **A mount already inside a solid block would freeze.** Not reachable by any in-plugin path (deploy spawns at the player's air position; the altitude clamp targets open air). The rider escapes by looking up and holding jump. One-line mitigation if it ever bites: also permit the move when the mount's *current* block is solid.
- **The altitude ceiling is effectively disabled under solid overheads.** `terrainHeightAt` uses `World.getHighestBlockAt`, which returns the Nether's bedrock roof (~Y 127), an overhang, or a cave ceiling. Permissive rather than dangerous — the clamp only teleports downward.
- **`clampAltitude`'s floor half is inert.** `applyAltitudeCeiling` only teleports when `clampedY < current.getBlockY()`, and `Math.max(groundY, …)` can only raise it. Harmless.
- **The viewer refresh is gated on the global tick**, so every active session refreshes on the same tick — a synchronised spike. Fine at realistic rider counts; stagger by `playerId.hashCode()` if that changes.
- **`WorldGuardRegionQuery.flightAllowed` swallows every exception with no logging, ever.** Fail-open is right, but with zero diagnostics a WorldGuard API move silently turns region checking into a permanent no-op. A log-once-then-suppress flag would make it diagnosable.
- Minor hygiene: null-guard asymmetry on the off-hand read; `FuelTank.refill()` dead in main; three write-only fields in `MagicCarpetPlugin`; `FlightGuardTest` builds the 14-component config positionally; `getRaw` called twice on the config warning path; an int key holding a non-integer that truncates to exactly the default warns silently; duplicate `"carpet"` literal for two different registries; `CarpetItem.create()` uses `getItemMeta()` unguarded.

**Stale comments to correct eventually:** `returnRug`'s Javadoc claims the off-hand is "guaranteed empty" for `QUIT` (a rider can swap an item in mid-flight — the code handles it, the comment is wrong); `CarpetManager` and `CarpetSession.mount()` assert the null-mount case "never reaches session registration" when it is only prevented incidentally by `CarpetVisual` NPE-ing.

**Design and configuration caveats:**

- **`flight.speed` applies to `seated` mode only.** `standing` — the Bedrock default — uses native client flight and ignores it. Documented in `config.yml` and `StandingFlightMode`'s Javadoc. Mapping blocks/tick onto `Player#setFlySpeed` (an unrelated 0.0–1.0 scale) was deliberately declined as a guess; retuning is a live-server decision.
- **Design §10.5 ("cruises in the facing direction") holds only in `seated` mode.** In `standing` the player flies where they push, not where they look, and the carpet is decoration. Consistent with design §3's trade-off, but §10.5 as written does not describe the Bedrock default path.
- **The 5% deploy fuel gate is a tuning value, not a verified one.** On defaults, 6s of ground recharge buys a 3s flight. Confirm the feel in game.
- **`TeleportFlag.EntityState.RETAIN_PASSENGERS` is `@Deprecated(forRemoval = true)`** as of Paper 1.21.10 with no replacement in `paper-api 26.1.2.build.74-stable` (independently javap-verified). It is the only passenger-preserving teleport and `setVelocity` is prohibited, so it is used deliberately. **This will need revisiting on the next Paper bump.**
- **`/carpet reload` does not resize an actively-flying rider's fuel tank** — deliberate; replacing it mid-flight would hand them a free refill. New settings apply on their next deploy.
- **Vertical input is additive**, so a level look plus jump yields magnitude `speed × √2` — the carpet climbs ~41% faster diagonally than it cruises level. Pinned by a test; retune in-game if it feels wrong.

**Withheld gates.** None. Status is `active`; all gates apply.

## 2. Repository

- [x] Repository is `carmelosantana/minecraft-magic-carpet` with an SSH `origin` and `main` branch.
      `https://github.com/carmelosantana/minecraft-magic-carpet` — public, `main` default,
      AGPL-3.0 detected by GitHub, `origin` =
      `git@github.com:carmelosantana/minecraft-magic-carpet.git`. Created and pushed by the
      operator on 2026-07-21 after `gh repo create` was denied by the harness permission
      classifier (not by this plugin's `autonomous` setting).
- [x] Existing user-owned worktree changes were identified and preserved.
      Working directory was empty apart from `docs/` written by gate 1; nothing to preserve.
- [x] No `herobrinesystems` references remain in source, metadata, workflows, remotes, or documentation.
      `rg -n 'herobrinesystems' . --hidden -g '!target/**' -g '!.git/**'` returns exactly one match:
      this checklist's own gate-2 checkbox text. Confirmed identical in `timber-blast` and
      `electric-furnace`, i.e. template text, not a reference.

**Gate 2 evidence.** Files committed (9): `LICENSE`, `README.md`, `pom.xml`, `.gitignore`,
`.github/workflows/build.yml`, `src/main/resources/plugin.yml`,
`src/main/java/org/xpfarm/magiccarpet/MagicCarpetPlugin.java`, `docs/PLUGIN_CHECKLIST.md`,
`docs/superpowers/specs/2026-07-21-magic-carpet-design.md`.

First `main` Actions run: [29864113294](https://github.com/carmelosantana/minecraft-magic-carpet/actions/runs/29864113294)
— **success**, 25s, 2026-07-21T20:03:12Z. Recorded here as gate 2 evidence only; the
gate 8 "successful main run before tagging" box belongs to `minecraft-plugin-release`
and stays unchecked.

## 3. Metadata

- [x] AGPL-3.0-or-later `LICENSE` and Maven license metadata are present and consistent.
      Full 661-line AGPL-3.0 text; `pom.xml` `<licenses>` names "GNU Affero General Public License
      v3.0 or later" at `https://www.gnu.org/licenses/agpl-3.0.html`.
- [x] `https://xpfarm.org` metadata and Carmelo Santana author metadata are present.
      `pom.xml` `<url>` and `<developers>`; `plugin.yml` `author` and `website`.
- [x] `play.xpfarm.org` is recorded as the public Minecraft server hostname where server identity is documented.
      `README.md` — "Play it on `play.xpfarm.org` (Java Edition and Bedrock Edition via Geyser)."
- [x] New work uses the `org.xpfarm` Maven group, or an existing-coordinate compatibility decision is documented.
      `org.xpfarm:magic-carpet:0.1.0`. No compatibility carve-out needed — this is new work.
- [x] Repository slug, artifact, releasable JAR, updater destination, and `plugin.yml` names are consistent.
      slug `magic-carpet` · project `<artifactId>magic-carpet</artifactId>` · JAR
      `magic-carpet-0.1.0.jar` · updater destination `magic-carpet.jar` · `plugin.yml` name
      `MagicCarpet` · package `org.xpfarm.magiccarpet`.
- [x] No secrets committed in source, defaults, tests, logs, history, or documentation.
      No credentials, tokens, private endpoints, or production configuration in any of the 9 files.

## 4. Compatibility

- [x] Java 25/Paper 26.1.2 build 74 compile succeeds and `plugin.yml` uses `api-version: '26.1'` (no longer a deviation — the template adopted `'26.1'` on `2026-07-21`; see `PLUGIN_LIFECYCLE.md` §4).
      Verified in the built JAR: embedded `plugin.yml` declares `api-version: '26.1'`,
      `main: org.xpfarm.magiccarpet.MagicCarpetPlugin`, version `0.1.0`.
- [x] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and declared.
      No hard dependencies. `softdepend: [WorldGuard, floodgate]`. **Both are resolved
      reflectively** (`Class.forName`) so no optional class is ever linked when the plugin is
      absent, and every reflection failure degrades to a safe default — `EditionResolver`
      treats everyone as Java, `WorldGuardRegionQuery` falls back to `RegionQuery.permissive()`.
      Load ordering: `WorldGuardRegionQuery.registerFlag` runs in `onLoad()`, **not**
      `onEnable()` — WorldGuard locks its `FlagRegistry` before any plugin's `onEnable()`.
- [x] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and protocol behavior.
      No chat-input prompts (Bedrock-hostile); all interaction is commands, a held item, and
      movement input, which Geyser translates. Edition detected via `FloodgateApi`, absent-safe.
      The dual-visual design (`BlockDisplay` for Java viewers, `FallingBlock` for Bedrock)
      exists specifically because Geyser never spawns display entities. `setVelocity` is
      prohibited plugin-wide for Bedrock reasons. See "Known limitations" §C9 for the upstream
      Geyser issues this design works around. Bedrock render behaviour itself is gate 7a's.

## 5. External services

- [x] External integrations are disabled by default or require explicit configuration and have bounded timeouts.
      **Not applicable — this plugin makes zero outbound network calls.** Verified: no HTTP
      client, no socket, no URL usage anywhere in the source.
- [x] Ollama/Umami-style external endpoints are optional and failure-tolerant when applicable.
      Not applicable; no external endpoints of any kind.
- [x] Endpoint failure cannot fail server/plugin startup, and diagnostics redact secrets.
      No endpoints. Startup safety is nonetheless enforced: `onEnable()` wraps its whole body,
      logs at SEVERE and disables only itself rather than propagating; config validation never
      throws for any input. No secrets exist to redact.

## 6. Tests and build

- [x] Unit tests cover separable logic, configuration, serialization, permissions, and failure paths where applicable.
      **107 tests across 10 files.** Every component with logic separable from the Bukkit
      runtime is covered: config parsing/validation (21), `FuelTank` (16), `FlightGuard` (16),
      `CarpetCommandParser` (19), `CarpetMotion` (10), `CombatGraceTracker` (9), `CarpetItem`'s
      pure half (6), `SessionTickOutcome` (4), `WorldGuardRegionQuery`'s reflective-absent path
      (4), `EditionResolver`'s reflective-absent path (2). The final review confirmed **no
      component whose testable logic went untested**. No mock framework — hand-rolled fakes only.
      The untestable classes need a live server and are recorded as gate 7a obligations below.
- [x] `mvn --batch-mode --no-transfer-progress clean verify` succeeds.
      Verified at controller level across three consecutive clean runs: exit 0, 107/107 passing.
      (One earlier run failed; traced to a concurrent Maven invocation on the same `target/`
      directory, not to the code. Not reproducible in three subsequent runs.)
- [x] The shaded releasable JAR and embedded `plugin.yml` were inspected; `original-*` JARs are excluded.
      `target/magic-carpet-0.1.0.jar`. **Zero** `org/bukkit`, `io/papermc` or `net/md_5` classes
      shaded in — the API correctly comes from the server classpath. `original-magic-carpet-0.1.0.jar`
      exists in `target/` as the pre-shade intermediate and is excluded from release assets by
      the workflow's `! -name 'original-*'` filter. Embedded `plugin.yml` verified: name, version,
      main class, `api-version`, `/carpet` + `magiccarpet` alias, all three permission nodes,
      `softdepend`.

## 7. Matrix

**7a (single-plugin runtime verification) — PASSED on the second attempt.** The first boot
**FAILED**: `MagicCarpet` was absent from `/plugins` entirely because `plugin.yml` had an
unquoted `": "` in its description, so SnakeYAML threw `ScannerException: mapping values are
not allowed here` and Paper logged `InvalidDescriptionException` without ever registering the
plugin. Fixed in `3b3aafe`, which also adds `PluginDescriptorTest` so the same class of defect
now fails at gate 6. **7b (full-roster matrix) is out-of-band and is not required for this
plugin's release** — it is triggered by an updater manifest change or a
Paper/Geyser/Floodgate/ViaVersion bump, not by a `dev` run.

- [ ] Fresh-volume [Legendary Java Minecraft Geyser Floodgate stack](https://github.com/TheRemote/Legendary-Java-Minecraft-Geyser-Floodgate) test covers every updater-managed plugin.
      **7b — out-of-band, not required for this release.**
- [ ] Each updater-managed plugin's manifest `enabled` value, default state, and expected fresh-volume behavior are recorded separately.
      **7b — out-of-band.** Magic Carpet is not yet enrolled in the updater (gate 10).
- [x] Paper, Geyser, Floodgate, and ViaVersion start successfully together.
      Fresh-volume disposable stack, `Done (19.322s)`, Java port served a real Minecraft
      handshake (`Paper 26.1.2 | protocol 775`). RCON `plugins` reported 4 plugins, **all green
      (`§a`)**: `floodgate`, `Geyser-Spigot (2.11.0-SNAPSHOT)`, `MagicCarpet (0.1.0)`,
      `ViaVersion (5.11.0)`. Note this also empirically settles the earlier Geyser/Paper
      protocol-mismatch scare — Geyser 2.11.0 and Paper 26.1.2 booted together cleanly with
      ViaVersion present.
- [x] Affected commands, permissions, persistence, and configuration reload were exercised over RCON with no server-wide hot reload.
      `/carpet`, `/carpet help`, `/carpet bogus` → help text, no stack trace.
      `/carpet off` from console → `Only players can use /carpet off.`
      `/carpet give` from console → `Console has no inventory. Specify a player: /carpet give <player>`
      `/carpet reload` → `Magic Carpet configuration reloaded.`
      Startup logged `Swept 0 orphaned carpet entities from a previous run.` and
      `Magic Carpet enabled.` Log grep for a server-wide reload: **0 matches**.
      **`onLoad()` WorldGuard flag registration verified live**: WorldGuard is absent from the
      test stack, `ClassNotFoundException` was caught, one WARNING logged, and the plugin
      continued with the permissive region query — the designed fail-open path, observed rather
      than assumed. No exceptions from `org.xpfarm` code. Stack torn down cleanly, no leaked
      containers or slot lease.
- [x] Ollama and Umami unavailable-endpoint tests keep the server and plugins available when applicable.
      Not applicable — no external services.

### Gate 7a runtime obligations

Carried verbatim from the final whole-branch review. None of these can be settled headlessly;
each needs the disposable stack, and several need a real client.

**These remain OPEN after the 7a pass.** Gate 7a proved the plugin loads, enables, and answers
commands. It attaches no client, so nothing below about rendering, movement, or item custody
in play was settled. Items 1–13 are the gate 12 play-test obligation.

1. **Verify the `QUIT` off-hand write actually persists.** Quit mid-flight, rejoin, check the
   off-hand. The analysis of CraftBukkit's `PlayerList.remove()` (quit event fires, then
   `save(entityplayer)`) says it does, and that is the basis for the change — but it is an
   inference, not an observation. If the write is observed *not* to persist on this build,
   restore the world drop for `QUIT` and record it as a known limitation instead.
2. **Ground detection via `player.isOnGround()` while mounted — top risk.** May misfire at block
   edges; carries vanilla's well-known false-positive/negative behaviour. The mount is a
   zero-hitbox marker ArmorStand moved by teleport, so its own flag would be worse.
3. **`CarpetItem`**: `create()` item build, glint, PDC round-trip; `isCarpet(ItemStack)` for any
   non-null stack; `recipe()` registration and actual crafting.
4. **`CarpetVisual`**: spawn, passenger attachment, scoreboard tag; Java `BlockDisplay` render
   (flat carpet, no night darkening, `FIXED` billboard) and **calibration of the `-0.6f` Y
   translation against a real seated rider**; Bedrock `FallingBlock` (visible, no fall, no
   despawn, no drop, no hurt); `refreshViewers` per-viewer show/hide with no flicker;
   `remove()` double-call and post-death idempotency.
5. **`EditionResolver.create()`** against real Java and real Floodgate connections.
6. **Both throwing deploy paths**, fuel exhaustion → fall damage, and the altitude clamp.
7. **Per-player exception isolation** with multiple simultaneous riders.
8. **`shutdownAll` / `sweepOrphans` leave zero orphans**; quit, death, chunk unload and plugin
   disable each leave zero orphaned entities (design §10.12).
9. **`applyConfig` live effect on active sessions** — confirm flying riders keep their fuel tank
   and grounded players' tanks rebuild from the new config.
10. **The solid-block collision stop and per-tick terrain sampling** — neither is unit-testable:
    `Material#isSolid()` throws `IllegalStateException: No RegistryAccess implementation found`
    outside a live Paper registry.
11. **Standing-mode altitude clamp feel** — the clamp teleports the player to an integer Y every
    tick while they hold jump above the ceiling; check for rubber-banding.
12. **`bedrock-mode: seated` against a real Bedrock client** — if the Geyser camera bug does not
    materialise, a patch release flips the default and Bedrock players sit too (design §3).
13. **Residual accepted risk**: if the standing-mode flight-flag restore throws, that player's
    `allowFlight`/`isFlying` may be left un-restored for one cycle. Bounded and self-healing.

## 8. CI/CD

- [x] Identical standard plugin Actions workflow is installed with the required triggers, Temurin 25 build, artifact, checksum, and release behavior.
      `.github/workflows/build.yml` copied verbatim; `diff` against `timber-blast`,
      `electric-furnace`, `wild-weather-update` and `curse` reports byte-identical. Includes the
      bare-filename `SHA256SUMS.txt` generation required by `GITHUB_ACTIONS.md`.
- [x] Successful main Actions run is recorded before tagging.
      Run [29878766771](https://github.com/carmelosantana/minecraft-magic-carpet/actions/runs/29878766771)
      — `completed` / `success`, 21s, commit `434ff10`. Its `headSha` was confirmed identical to
      local `HEAD` before tagging, so `v0.1.0` sits on the exact commit CI validated. No run was
      in flight at tag time.
- [x] Workflow permissions contain no broader access than the documented contract.
      `permissions: contents: write` only.

## 9. Release

- [x] Semantic version matches the POM, plugin metadata, and `v<version>` tag.
      `pom.xml` `<version>0.1.0</version>`; `plugin.yml` carries the `'${project.version}'`
      filter token rather than a hardcoded literal, so there is no drift to reconcile; the
      released JAR's embedded descriptor resolves to `version: '0.1.0'`. Tag `v0.1.0`,
      annotated, on commit `434ff10`. `git tag --list v0.1.0` was empty beforehand and the
      worktree was clean with no divergence from `origin/main`.
- [x] Successful tag Actions run and GitHub release are recorded.
      Tag run [29878922004](https://github.com/carmelosantana/minecraft-magic-carpet/actions/runs/29878922004)
      — success. Release
      [v0.1.0](https://github.com/carmelosantana/minecraft-magic-carpet/releases/tag/v0.1.0),
      published 2026-07-22T00:01:03Z by `github-actions[bot]`, `draft=false`,
      `prerelease=false`.
- [x] Release contains exactly one updater-matching JAR plus `SHA256SUMS.txt` and no `original-*` JAR.
      Exactly two assets: `magic-carpet-0.1.0.jar` (67780 bytes) and `SHA256SUMS.txt`
      (89 bytes). Zero `original-*` assets. `SHA256SUMS.txt` records the **bare** filename,
      not a `target/`-prefixed path, per the `GITHUB_ACTIONS.md` contract.
- [x] Downloaded release assets pass `sha256sum --check SHA256SUMS.txt`.
      Downloaded to a scratch directory and verified: `magic-carpet-0.1.0.jar: OK`, exit 0.
      The released JAR's embedded `plugin.yml` was additionally re-parsed as YAML to confirm
      the descriptor fix from `3b3aafe` is what actually shipped — it parses, and declares
      `MagicCarpet` / `0.1.0` / `api-version '26.1'` / the `carpet` command / all three
      permission nodes.

## 10. Updater

- [x] Updater manifest/tests cover repository, destination, anchored asset regex, legacy globs, enabled state, and optional pin.
      Manifest entry in `carmelosantana/minecraft-plugin-updater` commit
      [0f77c0a](https://github.com/carmelosantana/minecraft-plugin-updater/commit/0f77c0a),
      inserted alphabetically (1-line diff):
      `{"name": "Magic Carpet", "repo": "carmelosantana/minecraft-magic-carpet", "destination": "magic-carpet.jar", "asset_regex": "^magic-carpet-[0-9].*\\.jar$", "legacy_globs": ["magic-carpet-[0-9]*.jar"]}`
      No `enabled` key (absent = `true`, matching all 12 sibling entries) and **no pin** —
      it follows the latest non-prerelease release. `destination` is unique across all 13
      entries (checked programmatically). `python3 -m json.tool` clean; `python3 -m unittest
      discover -s tests` 11/11 pass. The `asset_regex` was verified **against the real v0.1.0
      release assets**: it selects exactly one JAR, and adversarial probes confirmed it rejects
      `original-magic-carpet-0.1.0.jar`, `magic-carpet-sources.jar`, another plugin's
      `timber-blast-1.0.0.jar`, and the bare destination `magic-carpet.jar`.
- [x] Fresh install, upgrade, no-op, legacy archival, endpoint failure, and checksum failure behaviors pass.
      **Fresh install**: `Magic Carpet: would install v0.1.0`.
      **Real install**: `installed v0.1.0`, 67780 bytes matching the release asset.
      **No-op**: second invocation reported `already current (v0.1.0)` — reached via the real
      install-then-repeat sequence, since `--dry-run` alone can never produce it.
      **Upgrade/replacement**: a stale 20-byte JAR at the destination was backed up to
      `magic-carpet.jar.<ts>.bak` and replaced with the real release.
      **Legacy archival**: a seeded `magic-carpet-0.0.9.jar` was moved out of the plugins
      directory to `magic-carpet-0.0.9.jar.<ts>.legacy.bak` — observed via a real install, as
      dry-run mode never reports archival.
      **Endpoint failure**: a manifest pointing Magic Carpet at a nonexistent repo produced
      `completed with 1 warning(s)`, exit `0`; the other 12 plugins continued normally.
      **Checksum failure**: covered by `tests/test_updater.py::test_bad_checksum_preserves_installed_jar`,
      which mocks a mismatched `SHA256SUMS` and asserts both that `UpdateError` is raised and
      that the previously installed JAR's bytes are unchanged. Passed.
- [x] Updater dry-run uses a disposable directory and never a production plugin directory.
      All runs targeted `/tmp/minecraft-plugin-updater-dry-run`. The non-dry-run invocations
      required by the no-op and archival checks overrode **all three** paths — `--plugins-dir`,
      `--state-file` and `--backup-dir` — inside that sandbox, so none fell through to their
      `/minecraft/...` production defaults. The whole tree was discarded afterward.
- [x] Failure retains the installed JAR and default fail-open behavior permits Minecraft startup.
      Verified by sha256: the installed `magic-carpet.jar` was byte-identical before and after
      the unreachable-endpoint run (`3e3ac29c71ff342e…`). The run exited `0` without
      `--strict`, so a plugin-level failure warns and continues rather than aborting the batch
      — it cannot block Minecraft startup.

## 11. Deployment

Gate 11 is operator-mediated — the agent prepares and verifies, the operator triggers. Leaving
these unticked with a note that the redeployment is pending the operator is an accurate resting
state, not a failure.

- [ ] Full Dokploy redeployment/recreation was performed by the operator (not a container restart), and the recreation used is noted.
- [ ] Operator-relayed evidence was verified: `plugin-updater` exit `0`, Minecraft started after it, each covered plugin's updater line, and clean enable lines for Paper/Geyser/Floodgate/ViaVersion and every covered plugin.
- [ ] No production plugin hot reload was used.

## 12. Handoff

- [ ] Current-state documentation refreshed with release, CI, updater, deployment, and local pending state.
- [ ] Known limitations, skipped checks, configuration or migration notes, rollback guidance, and follow-up owner are recorded.
- [ ] Evidence distinguishes source commit, published tag/release, updater state, and deployed state without exposing secrets.
- [ ] Client play-test obligation recorded with a named owner and a target date: `<owner>` / `<date>`.
- [ ] Client play-test outcome recorded once performed, covering Java join, Bedrock join, and any form, inventory, or rendered item behavior this plugin introduces. Leave unchecked with the owner and date above until the team has run it; an unchecked box here does not block a release, but an unrecorded obligation is a gate 12 failure.
- [ ] Public deployment reachability confirmed during that pass: `play.xpfarm.org` reaches the intended Java and Bedrock entry points.
