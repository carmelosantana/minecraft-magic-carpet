# Magic Carpet — Design

- Date: 2026-07-21
- Plugin: `Magic Carpet` (`magic-carpet`)
- Status: design approved, pre-implementation (lifecycle gate 1)

An enchanted rug carried in the off-hand. Jump while holding it and the carpet
materialises underfoot; the player flies where they look until they sneak back
down to the ground, where the rug returns to their hand.

## 1. Player experience

1. Craft the rug, or receive one from `/carpet give`.
2. Hold it in the **off-hand**. On the ground it is an ordinary held item.
3. **Jump.** The carpet unfurls under the player and flight begins.
4. Steer by looking: the carpet cruises forward in the facing direction, and
   looking up or down climbs or dives.
5. **Sneak** to lose altitude. Touching the ground stows the carpet and returns
   the rug to the off-hand.
6. A charge drains while airborne and refills on the ground. **Run the charge
   dry in mid-air and the carpet vanishes — the player falls and takes fall
   damage.**

The carpet has one fixed signature appearance. Deploying and stowing play a
sound and emit particles.

## 2. The binding constraint

The requirement that other players see the rider **seated** dictates the entire
architecture, so it is recorded here in full.

The vanilla client chooses the sitting animation from a single flag. In
`HumanoidMobRenderer`:

```java
state.isPassenger = entity.isPassenger();
```

`AvatarRenderer` (the class named `PlayerRenderer` before 26.1) uses the same
extractor, so players follow the identical path, and `HumanoidModel` bends the
legs ~81° whenever `state.isPassenger` is true. There is no per-vehicle hook —
NeoForge had to patch that exact line to introduce `shouldRiderSit()`, which is
positive evidence that vanilla exposes no such switch.

**Therefore riding any entity produces a seated rider.** The vehicle type is
irrelevant to the pose. `Entity#setPose(Pose.SITTING)` does *not* substitute:
the renderer keys on `isPassenger`, not on the pose enum.

The cost is fixed and unavoidable. `Player.travel()` short-circuits before
flight abilities are consulted:

```java
if (this.isPassenger()) {
    super.travel(input);   // flight abilities never reached
} else if (this.getAbilities().flying) { ... }
```

> **Seated implies passenger implies the server must move the vehicle.** A
> player cannot be simultaneously seated and in control of their own flight.

The only client-authoritative vehicles in vanilla are the boat, horse, pig,
strider and happy ghast. Every one is either permanently visible (`setInvisible`
is explicitly undefined for non-living entities such as boats) or bound to a
visible harness. A plugin-defined vehicle is always server-authoritative, and
NMS is not an escape hatch — Paper 26.1 removed the internal remapper entirely.

"Flies where you look" is chosen precisely because it is a server-driven model.
Free creative-style flight would have forced a standing pose.

## 3. Flight modes

Both editions are configured symmetrically. Neither is privileged in code.

```yaml
flight:
  java-mode: seated       # seated | standing
  bedrock-mode: standing  # seated | standing
```

A `FlightMode` strategy is selected per player at deploy time from their
edition (via `FloodgateApi#isFloodgatePlayer`) and the matching config key.

### `SeatedFlightMode`

An invisible ArmorStand vehicle carries the player.

| Property | Value | Reason |
| --- | --- | --- |
| `setInvisible(true)` | — | ArmorStand is a `LivingEntity`, so invisibility is defined and honoured on both editions |
| `setMarker(true)` | — | zero hitbox |
| `setSmall(true)` | — | attachment geometry |
| `setGravity(false)` | — | server owns the position |
| `setBasePlate(false)` | — | no stray render |
| `setInvulnerable(true)` | — | cannot be killed out from under the rider |
| `setCollidable(false)` | — | no physics interference |
| `setPersistent(false)` | — | never saved to disk |
| no equipment, no nametag | — | Geyser sets `EntityFlag.INVISIBLE` only when the nametag is empty |
| scoreboard tag | `magiccarpet` | orphan sweep on startup |

The stand is moved with `teleport(location, TeleportFlag.EntityState.RETAIN_PASSENGERS)`.

**`setVelocity` is never used.** Falling-block velocity does not move on Bedrock
(Geyser #5655, closed "Can't Fix"), and velocity applied to a mount flings
Bedrock riders off (Geyser #6454).

ArmorStand is chosen over `Interaction` and over display entities as the vehicle
because Geyser interpolates movement only for entities extending its
`LivingEntity` class. `Interaction` and the displays extend plain `Entity`, which
has no `lerpSteps`/`lerpPosition` at all (Geyser #6551), so anything riding them
drags and snaps on Bedrock.

### `StandingFlightMode`

`setAllowFlight(true)` + `setFlying(true)`, with the visual tracking the player.
The client flies itself, so there is no interpolation lag and no camera risk.
Geyser translates player abilities cleanly with no open regressions. The player
renders standing — creative flight has no dedicated pose and resolves to
`Pose.STANDING`.

This is the Bedrock default because it **guarantees** flight for Bedrock players
regardless of the unresolved upstream camera bugs in §8. Gate 7a tests
`bedrock-mode: seated` against a real Bedrock client; if the camera bug does not
materialise, a patch release flips the default and Bedrock players sit too.

## 4. Carpet visual

Geyser has never spawned Block or Item Displays — `JavaAddEntityTranslator`
returns early on a null definition, and Geyser #3810 has been open since
2023-06-03. A single visual entity cannot serve both editions, so two are
spawned, both `setVisibleByDefault(false)` and revealed per viewer with
`Player#showEntity`.

| Viewer | Entity | Configuration |
| --- | --- | --- |
| Java | `BlockDisplay` | carpet `BlockData`; transformation scale `(N, 1, N)` so it stays carpet-thin under non-uniform scaling; translation ≈ `(-0.5, -0.6, -0.5)`; `displayWidth`/`displayHeight` left at `0`; explicit `setBrightness`; `Billboard.FIXED` |
| Bedrock | `FallingBlock` | `setGravity(false)`, `shouldAutoExpire(false)`, `setCancelDrop(true)`, `setHurtEntities(false)` |

Both are attached as **passengers of the ArmorStand**, not teleported each tick.
The client then positions them every frame against the already-interpolated
vehicle: no trailing, four packets total for the lifetime of the attachment, and
it sidesteps the still-open Paper #11694, where the server's periodic position
resync (`tickCount % 60 == 0`) visibly breaks display interpolation. Paper's own
display-entity documentation recommends this per-viewer pattern.

Two offsets need empirical calibration in-game, independently:

- **Java.** Passenger attachment is index-clamped, not per-seat. ArmorStand
  declares no `passengerAttachments`, so every passenger clamps to one point.
  The player lands at `P - (0, 0.6, 0)` (the player's own vehicle attachment)
  while the display lands at `P`, putting the carpet at the rider's waist. The
  `-0.6` in the transformation translation corrects this. Translation is purely
  visual — `Display.tick()` guards all transformation consumption behind
  `isClientSide()`, and the culling box derives from width/height, never from
  translation — so it moves the model without moving the entity.
- **Bedrock.** Geyser applies a hardcoded `yOffset += 0.995f` for falling-block
  mounts. It is not controllable from the server, so the Bedrock carpet will sit
  roughly a block off from the Java one.

`displayWidth`/`displayHeight` are deliberately left at `0` (no culling). Large
values are a known renderer hazard — a display sized 9999 drove Sodium to
~1.5 s/frame.

## 5. Input

**Deploy** uses `com.destroystokyo.paper.event.player.PlayerJumpEvent`. Paper
fires it on exactly the transition wanted:

```java
boolean movedUpwards = yDist > 0.0;
if (this.player.onGround() && !packet.isOnGround() && movedUpwards) { ... }
```

That check sits inside the `else` branch of `if (this.player.isPassenger())`, so
the event **never fires while riding**. It cannot be reused for in-flight ascent.
It also does not fire when walking off a ledge, which is the desired behaviour
here — stepping off a cliff must not deploy the carpet.

**Steering** polls `Player#getCurrentInput()` on a repeating task.
`PlayerInputEvent` is *not* used as a tick source: Paper's `handlePlayerInput`
guards it with `if (!packet.input().equals(this.player.getLastClientInput()))`,
so it is edge-triggered and fires only on change. `Player#isJumping()` is
likewise unusable — it is always false for players (Paper #13565); the
maintainer's recommendation is `Input#isJump()`.

Geyser builds the Java input packet from `PlayerAuthInputPacket`, so Bedrock
input works. One caveat to test separately: only mouse and touch-with-classic
input modes yield discrete flags. Gamepad and touch-crosshair send an analogue
vector thresholded to booleans, giving no magnitude and a different diagonal
feel.

## 6. Components

| Component | Responsibility |
| --- | --- |
| `MagicCarpetPlugin` | bootstrap, config load, orphan sweep on enable |
| `CarpetItem` | `ItemStack` factory, PDC marker key, recipe registration |
| `CarpetSession` | per-player state: mode, entities, fuel, deploy location |
| `CarpetManager` | session registry, repeating tick task, teardown |
| `FlightMode` | interface: `deploy`, `tick(Input)`, `dismiss` |
| `SeatedFlightMode` | ArmorStand vehicle (§3) |
| `StandingFlightMode` | native flight (§3) |
| `CarpetVisual` | dual visual, per-viewer visibility (§4) |
| `FuelTank` | drain, recharge, exhaustion |
| `FlightGuard` | world, region, altitude and combat checks |
| `CarpetCommand` | `give`, `off`, `reload` |
| `CarpetListeners` | jump, dismount, damage, quit, death, chunk unload |

Each unit is independently testable. `FuelTank`, `FlightGuard` and the config
parser have no Bukkit dependency in their core logic and carry the unit tests.

## 7. Events, permissions, config, persistence

**Events consumed**

| Event | Purpose |
| --- | --- |
| `PlayerJumpEvent` | deploy trigger |
| `EntityDismountEvent` | cancel dismounts the plugin did not initiate |
| `EntityDamageEvent` | combat forces a descent |
| `PlayerQuitEvent`, `PlayerDeathEvent` | teardown, no orphans |
| `ChunkUnloadEvent`, `PluginDisableEvent` | teardown, no orphans |
| `PrepareItemCraftEvent` | enforce the craft permission |

**Permissions**

| Node | Default | Gates |
| --- | --- | --- |
| `magiccarpet.use` | `true` | deploying a carpet |
| `magiccarpet.craft` | `true` | crafting the rug |
| `magiccarpet.admin` | `op` | `/carpet give`, `/carpet reload` |

**Configuration** (`config.yml`)

```yaml
flight:
  java-mode: seated
  bedrock-mode: standing
  speed: 0.5              # blocks/tick
  altitude-ceiling: 64    # blocks above terrain
fuel:
  capacity-seconds: 60
  recharge-seconds: 120   # ground time for a full refill
combat:
  drop-on-damage: true
  grace-ticks: 40
worlds:
  mode: deny-list         # allow-list | deny-list
  list: []
worldguard:
  respect-regions: true
effects:
  particles: true
  sound: true
```

All numeric keys are range-validated on load; an invalid value logs a warning
and falls back to the default rather than failing plugin enable.

**Persistence.** A PDC marker key on the item identifies a rug. Everything else
— sessions, fuel, entities — is in-memory and deliberately not persisted; a
restart lands every rider safely rather than restoring them mid-air.

**Dependencies.** No hard dependencies. Soft: WorldGuard (region checks),
Floodgate (edition detection). Both are absent-tolerant — without Floodgate
every player is treated as Java.

## 8. Known limitations

Upstream, not fixable in this plugin:

- **Geyser #3810** (open since 2023-06-03) — Block/Item Displays are never
  spawned for Bedrock. This is why §4 needs two visuals.
- **Geyser #6454 / #5911** (both open, untriaged) — a Bedrock rider's camera may
  not update on a server-moved mount: "the Bedrock player's screen remains
  static." This is the reason `bedrock-mode` defaults to `standing`.
- **Geyser #5017** (open, confirmed, untouched ~22 months) — seated players float
  for Bedrock viewers.
- **Geyser #6551** (open, filed 2026-07-16) — non-living entities are not
  interpolated. Constrains the vehicle to ArmorStand.
- **Paper #11694** (open; fix PR #11695 closed unmerged) — periodic position
  resync breaks display interpolation. Avoided by attaching as passengers.
- Bedrock carpet offset ≈ 1 block from the Java visual, from Geyser's hardcoded
  `+0.995f` falling-block mount offset. Not server-controllable.
- Gamepad and touch-crosshair Bedrock input arrive as a thresholded analogue
  vector, so diagonal steering feel differs. Test separately.

Deliberately out of scope for v1:

- **Passengers.** A second rider is v2 — it turns the vehicle into a multi-seat
  positioning problem.
- **Resource pack.** Explicitly declined. Worth recording that a pack would flip
  the design: a scale-0.25 Happy Ghast with transparent body and harness textures
  yields a seated rider with true client-authoritative 3-axis flight on both
  editions and no server movement code. It is rejected here only because of the
  no-pack constraint, not on merit. Without a pack the harness is unavoidable —
  `shouldRenderLayers()` returns `true` and is never overridden — the hitbox is
  4×4×4, and any player standing within a block of the ghast makes the pilot lose
  control.

Forward-looking: **Mojang ships `minecraft:cushion` in 26.3** — a first-party
placeable sittable entity, one passenger, no collision. Treat it as a future
integration point or naming conflict.

## 9. Ecosystem findings

Two items surfaced by research that are **not** specific to this plugin and need
an owner:

1. **Geyser version mismatch (operational blocker).** Geyser 2.11.0 (build 1185+,
   since 2026-07-10) speaks Java protocol 776 = MC 26.2. The server runs Paper
   26.1.2. The last Geyser genuinely supporting 26.1.x is **2.10.1 build 1184**.
   Geyser's own `getJavaVersions()` string still lists 26.1.2 — a stale leftover
   never updated by the 26.2 bump, and it should not be trusted. Either pin
   Geyser to 2.10.1-b1184 or move the stack to Paper 26.2. Whether ViaVersion can
   bridge the gap is **unverified**.

   > **SUPERSEDED `2026-07-21` — investigated, not a break, no action taken.** The
   > original text above is left intact as the historical record; do not act on it.
   > The 776-vs-775 gap is real, but ViaVersion is Geyser's *own* designed
   > mechanism for it: `GeyserSpigotVersionChecker.checkForSupportedProtocol`
   > delegates entirely to ViaVersion when present and never compares protocols
   > itself, and Geyser's own locale string instructs users to *"install
   > ViaVersion"* when it is absent. ViaVersion 5.11.0 registers both
   > `775 -> "26.1-26.1.2"` and `776 -> "26.2"`. `getJavaVersions()` is
   > `List.of(codec.getMinecraftVersion(), "26.1.1", "26.1.2")` — a dynamic first
   > element plus a maintained down-level list, not a stale string — and the `26.x`
   > values in `GameProtocol` are **Bedrock** versions, the likely source of the
   > misreading. Verified against the running JAR's bytecode, clean boot logs, and a
   > live production Bedrock ping. See the toolkit's `CURRENT_STATE.md`.
2. **`api-version` is stale in the toolkit template.** The template specifies
   `'1.21'`. The valid range is now 1.13–26.2, and `Commodore` silently rewrites
   `Cow`→`AbstractCow` and `Slime`→`AbstractCubeMob` for plugins below those
   thresholds. Harmless for this plugin, but this plugin uses `'26.1'` and the
   template should be revisited.

Also note that Paper 26.1 changed the Maven coordinate shape to
`26.1.2.build.74-stable` rather than `-R0.1-SNAPSHOT`.

## 10. Acceptance checks

1. The recipe yields a rug carrying the PDC marker; `/carpet give` yields the same item.
2. Holding the rug in the off-hand and jumping from the ground deploys the carpet.
3. In `seated` mode the player is a passenger of an invisible ArmorStand and renders seated to other players.
4. A Java viewer sees a `BlockDisplay` carpet at the rider's feet; a Floodgate viewer sees a `FallingBlock` carpet.
5. Looking up and down changes altitude; the carpet cruises in the facing direction.
6. Sneaking descends; ground contact stows the carpet, removes every spawned entity, and returns the rug to the off-hand.
7. Fuel drains while airborne and recharges on the ground at the configured rates.
8. Fuel exhaustion in mid-air dismounts the rider, who then falls and takes fall damage.
9. The altitude ceiling clamps upward movement.
10. Taking damage while flying forces a descent.
11. Deploying in a denied world or a WorldGuard-protected region is refused with a player-visible message.
12. Quit, death, chunk unload and plugin disable each leave zero orphaned entities.
13. `/carpet reload` applies configuration changes without a server restart.
14. In `standing` mode the player flies under native client flight, and a Bedrock player completes a full deploy → fly → land cycle.
15. Invalid configuration values log a warning, fall back to defaults, and do not fail plugin enable.

## 11. Naming chain

| Link | Value |
| --- | --- |
| Slug | `magic-carpet` |
| Repository | `carmelosantana/minecraft-magic-carpet` |
| Maven | `org.xpfarm:magic-carpet` |
| Package | `org.xpfarm.magiccarpet` |
| `plugin.yml` name | `MagicCarpet` |
| Releasable JAR | `magic-carpet-<version>.jar` |
| Updater destination | `magic-carpet.jar` |
| Command | `/carpet` (alias `/magiccarpet`) |

## 12. Sources

Feasibility findings in §2–§5 and §8 come from a dispatched research pass
(2026-07-21) against the Paper 26.1.2 build 74 javadocs, the vanilla 26.1
decompile, Geyser master source and issue tracker, and the Paper issue tracker.
Items marked unverified there are carried into §8 and §9 as unverified rather
than being resolved by assumption.
