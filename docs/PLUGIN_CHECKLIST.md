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

**`api-version` deviates from the template**: this plugin uses `'26.1'`, not
`'1.21'`. See §1 Known limitations and the design doc §9.

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

- **Geyser version mismatch.** Geyser 2.11.0 (build 1185+, since 2026-07-10) speaks Java protocol 776 = MC 26.2; the server runs Paper 26.1.2. The last Geyser genuinely supporting 26.1.x is **2.10.1 build 1184**. Geyser's own `getJavaVersions()` still lists 26.1.2 — a stale leftover from the 26.2 bump, not to be trusted. Pin Geyser or move the stack to Paper 26.2. Whether ViaVersion bridges it is **unverified**.
- **Toolkit template `api-version`.** The template specifies `'1.21'`; valid range is now 1.13–26.2, and `Commodore` silently rewrites `Cow`→`AbstractCow` and `Slime`→`AbstractCubeMob` below those thresholds. Harmless here, but the template should be revisited.

**Withheld gates.** None. Status is `active`; all gates apply.

## 2. Repository

- [ ] Repository is `carmelosantana/minecraft-magic-carpet` with an SSH `origin` and `main` branch.
- [ ] Existing user-owned worktree changes were identified and preserved.
- [ ] No `herobrinesystems` references remain in source, metadata, workflows, remotes, or documentation.

## 3. Metadata

- [ ] AGPL-3.0-or-later `LICENSE` and Maven license metadata are present and consistent.
- [ ] `https://xpfarm.org` metadata and Carmelo Santana author metadata are present.
- [ ] `play.xpfarm.org` is recorded as the public Minecraft server hostname where server identity is documented.
- [ ] New work uses the `org.xpfarm` Maven group, or an existing-coordinate compatibility decision is documented.
- [ ] Repository slug, artifact, releasable JAR, updater destination, and `plugin.yml` names are consistent.
- [ ] No secrets committed in source, defaults, tests, logs, history, or documentation.

## 4. Compatibility

- [ ] Java 25/Paper 26.1.2 build 74 compile succeeds and `plugin.yml` uses `api-version: '26.1'` (deliberate deviation from the template's `'1.21'` — see §1 Known limitations).
- [ ] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and declared.
- [ ] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and protocol behavior.

## 5. External services

- [ ] External integrations are disabled by default or require explicit configuration and have bounded timeouts.
- [ ] Ollama/Umami-style external endpoints are optional and failure-tolerant when applicable.
- [ ] Endpoint failure cannot fail server/plugin startup, and diagnostics redact secrets.

## 6. Tests and build

- [ ] Unit tests cover separable logic, configuration, serialization, permissions, and failure paths where applicable.
- [ ] `mvn --batch-mode --no-transfer-progress clean verify` succeeds.
- [ ] The shaded releasable JAR and embedded `plugin.yml` were inspected; `original-*` JARs are excluded.

## 7. Matrix

- [ ] Fresh-volume [Legendary Java Minecraft Geyser Floodgate stack](https://github.com/TheRemote/Legendary-Java-Minecraft-Geyser-Floodgate) test covers every updater-managed plugin.
- [ ] Each updater-managed plugin's manifest `enabled` value, default state, and expected fresh-volume behavior are recorded separately.
- [ ] Paper, Geyser, Floodgate, and ViaVersion start successfully together.
- [ ] Affected commands, permissions, persistence, and configuration reload were exercised over RCON with no server-wide hot reload.
- [ ] Ollama and Umami unavailable-endpoint tests keep the server and plugins available when applicable.

## 8. CI/CD

- [ ] Identical standard plugin Actions workflow is installed with the required triggers, Temurin 25 build, artifact, checksum, and release behavior.
- [ ] Successful main Actions run is recorded before tagging.
- [ ] Workflow permissions contain no broader access than the documented contract.

## 9. Release

- [ ] Semantic version matches the POM, plugin metadata, and `v<version>` tag.
- [ ] Successful tag Actions run and GitHub release are recorded.
- [ ] Release contains exactly one updater-matching JAR plus `SHA256SUMS.txt` and no `original-*` JAR.
- [ ] Downloaded release assets pass `sha256sum --check SHA256SUMS.txt`.

## 10. Updater

- [ ] Updater manifest/tests cover repository, destination, anchored asset regex, legacy globs, enabled state, and optional pin.
- [ ] Fresh install, upgrade, no-op, legacy archival, endpoint failure, and checksum failure behaviors pass.
- [ ] Updater dry-run uses a disposable directory and never a production plugin directory.
- [ ] Failure retains the installed JAR and default fail-open behavior permits Minecraft startup.

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
