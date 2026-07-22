# Field report — Java player, v0.1.0

- Date: 2026-07-22
- Version: `v0.1.0`
- Reporter: a Java Edition player on `play.xpfarm.org`
- Status: **both fixed in source, neither confirmed in play.** See "Fix applied" at the end.

This is the first real client feedback on Magic Carpet. Both reports are genuine defects and
both map onto specific lines. Notably, **both were already recorded as risks** before release —
one as a known limitation, one as the explicitly-named top gate-7a risk — and both materialised
in the first session of real play.

## Report 1 — "Bumped into a wall of dirt and died, said he suffocated in a wall. Wasn't fall damage."

**Severity: high — player death, trivially reproducible.**

### Root cause (near-certain from source)

`SeatedFlightMode.java:172-175` is the only block check in the entire movement path:

```java
private static boolean isBlocked(Location destination) {
    World world = destination.getWorld();
    return world != null && world.getBlockAt(destination).getType().isSolid();
}
```

It samples **exactly one block, at the mount's own position**. But the mount is a zero-hitbox
marker ArmorStand, and the thing that actually collides with terrain is the *rider*:

- The rider sits at `mount − (0, 0.6, 0)` (`CarpetVisual.java:53-59` documents this offset).
- A player hitbox is 0.6 wide × **1.8 tall**.
- So the rider occupies roughly `mount.y − 0.6` through `mount.y + 1.2` — **two block layers**,
  neither of which is guaranteed to be the single block `isBlocked` sampled.

Flying at a dirt wall, the block at the mount's exact position can be air while the rider's
head block is dirt. `isBlocked` returns false, the teleport proceeds, and the rider's head ends
up inside solid terrain → suffocation damage.

Confirmed by grep: `getBlockAt` appears exactly once in the whole plugin, at that line. Nothing
ever checks the block above or below the mount.

### Why it killed rather than merely hurt

Suffocation ticks repeatedly. `combat.drop-on-damage` is `true` by default, so the first
suffocation tick dismisses the session with `COMBAT` — which drops the rider **inside the wall
they are already embedded in**, where they keep suffocating with no carpet left to fly out on.
The combat-drop feature turns a graze into a death.

### Prior record

Recorded pre-release as known limitations A1 and A2 ("collision is destination-only, so high
speeds tunnel" / "a mount already inside a solid block would freeze"). Both framed the risk as
*tunnelling at high speed*. The real defect is more basic and present at the default speed: the
check tests the wrong volume, not the wrong distance. The final review reasoned that a rider
could always turn away — true for the mount, but it never modelled the rider's own 1.8-tall
hitbox as the thing that collides.

### Fix direction (not applied)

Check the volume the **rider** occupies, not a single point at the mount:

- At minimum, test the rider's feet block and head block at the destination (`mount.y − 0.6`
  and `mount.y + 1.2`), not just `mount.y`.
- Consider `Location#getBlock().getCollisionShape()` or `World#getBlockAt(...).isPassable()`
  rather than `Material#isSolid()` — `isSolid()` is a material property and misreports several
  passable blocks (and is also why this is untestable headlessly: it needs a live registry).
- Keep it cheap. This runs every tick per rider; two block lookups is fine, a raycast is not.
- Separately, consider whether `combat.drop-on-damage` should ignore suffocation
  (`DamageCause.SUFFOCATION`) — dropping a rider who is already inside a block strictly worsens
  their situation. Same argument arguably applies to `DamageCause.VOID` and fall damage.

## Report 2 — "I wasn't able to dismount. How do we dismount?"

**Severity: high — soft-lock. The player has no discoverable way out of flight.**

### Root cause (strong hypothesis — verify on a live server first)

`CarpetManager.java:655`:

```java
boolean grounded = player.isOnGround();
```

The rider is a **passenger** of the mount. In vanilla, a passenger's own `onGround` flag is not
what tracks ground contact — the *vehicle* carries that state, and the passenger's flag is
expected to stay `false` for the whole ride. If that holds, `grounded` is **never true while
flying**, so `SessionTickOutcome.decide` never returns `LANDED`, and the carpet never
auto-stows on ground contact.

This was recorded verbatim as the **#1 gate-7a runtime obligation and "top risk"**:

> Ground detection via `player.isOnGround()` while mounted — top risk. May misfire at block
> edges; carries vanilla's well-known false-positive/negative behaviour.

The pre-release framing was "may misfire." The player's report suggests it does not fire at all.

### Why every other exit is also closed

The design deliberately has **no dismount input**:

- **Sneak** descends; it does not dismount (`flight.java-mode: seated` design §1).
- **Shift-off / any manual dismount** is actively cancelled — `CarpetListeners` cancels
  `EntityDismountEvent` for dismounts the plugin did not initiate, precisely so a rider cannot
  eject mid-air.
- **Ground contact** is the intended exit — and per the above, it likely never triggers.

That leaves `/carpet off` as the only working exit, and nothing in-game tells the player it
exists. The item lore describes hold / jump / sneak, not how to get off.

So the practical player experience is: fly until the fuel runs dry, then get dropped with fall
damage. That is the `FUEL_EMPTY` path working exactly as designed, reached because the
`LANDED` path is broken.

### Fix direction (not applied)

Two separate problems; fix both.

1. **Ground detection.** Verify first on a live server whether `player.isOnGround()` is ever
   true for a passenger. If not, detect landing another way — sample the block beneath the
   mount for solidity, or compare the mount's Y against the terrain height already computed by
   `CarpetManager.terrainHeightAt` (`CarpetManager.java:747`). Whatever is chosen must be
   verified in play, not reasoned about — reasoning is what produced this bug.
2. **Discoverability.** Even with landing fixed, a rider should have an explicit, obvious way
   off. Options worth weighing: make sneak-while-already-descending-at-ground-level dismount;
   allow the vanilla dismount key by *not* cancelling `EntityDismountEvent` when the rider is
   within a block or two of the ground; add the dismount hint to the item lore and to the
   deploy message. Whatever is picked, tell the player — the current lore never mentions
   `/carpet off`.

## Cross-cutting note

Both defects are in the seated flight path, which is the **Java** default. Bedrock players
default to `standing` mode, which uses native client flight and neither teleports the rider nor
cancels dismounts — so Bedrock is probably unaffected by both. Worth confirming rather than
assuming.

## Open question — how did v0.1.0 reach the player?

Gate 11 (deployment) was never run or recorded in the session that built this. The plugin is
enrolled in the updater manifest and the release is published, so a redeployment would install
it — but there is no recorded evidence of that deployment. Establish what is actually running on
`play.xpfarm.org` before shipping a fix, so the fix is known to land on top of the right build.

## Fix applied — 2026-07-22

A follow-up report from the same player closed the open question above: **"when we landed, we
stayed seated."** That is direct confirmation that `LANDED` never fires — the rider reached the
ground and the session did not end. Report 2's root cause is now observed, not hypothesised.

### What changed

Both bugs turned out to be the same mistake — approximating the rider's body with a single point —
and both are fixed by the same new class, `RiderClearance`, which asks the world about the rider's
**actual hitbox**.

The pinned `paper-api 26.1.2.build.74-stable` turned out to expose exactly the right primitives,
which the pre-release research never found: `RegionAccessor#hasCollisionsIn(BoundingBox)` and
`Entity#getBoundingBox()` (both confirmed with `javap` against the pinned jar). That is a better
outcome than the fix directions above assumed — no hand-rolled multi-block sampling, and, notably,
**no dependency on the uncalibrated `-0.6` offset** in `CarpetVisual`. The world is asked where the
rider actually is rather than told.

| File | Change |
| --- | --- |
| `flight/RiderClearance.java` | New. `isGrounded`, `collidesAfterMoving`, `isEmbedded`, plus the pure `groundProbe` geometry. |
| `flight/SeatedFlightMode.java` | Collision now tests the rider's box, not the mount's block. Already-embedded riders may still move (fixes limitation A2). Deploy lifts the mount 1.2 blocks when the rider fits there. |
| `session/CarpetSession.java` | New `hasBeenAirborne` latch. |
| `session/SessionTickOutcome.java` | `decide` takes the latch; `LANDED` requires it. |
| `session/CarpetManager.java` | Landing and fuel recharge both use `RiderClearance.isGrounded` instead of `player.isOnGround()`. |
| `listener/CarpetListeners.java` | `combat.drop-on-damage` no longer fires on `SUFFOCATION`. |
| `item/CarpetItem.java` | Lore line: "Land to stow it, or /carpet off." |

Two design notes worth carrying forward:

- **The takeoff latch.** Deploy happens from a jump, so the rider starts on the ground; without a
  latch, working landing detection would stow the carpet on the tick it deployed. Landing arms only
  after the rider has been clear of the ground once. Latching on the observed state rather than
  counting grace ticks avoids depending on how fast a passenger's position propagates after the
  mount spawns — timing this plugin does not control, and the kind of assumption that produced
  bug 2 in the first place.
- **The deploy lift** is skipped when the rider would not fit (low ceiling). They keep flying, since
  the latch is unarmed, and can climb out by looking up or holding jump.

### Verification status — read this before shipping

- `mvn verify` green: **120 tests**, up from 107. New coverage: `RiderClearanceTest` (probe
  geometry) and two latch cases in `SessionTickOutcomeTest`.
- Gate 7a re-run green: plugin loads and enables on Paper 26.1.2, `/carpet reload` works, no new
  exceptions. Only the expected WorldGuard-absent warning.
- **Neither fix is confirmed to work.** Gate 7a attaches no client, so nothing here has flown a
  carpet, hit a wall, or landed. Every test added is of geometry and decision logic; the
  `hasCollisionsIn` calls that make the fix real cannot be exercised without a server and a player.

That is the same blind spot that let both bugs ship. It has not been closed — it has been narrowed.
The fixes rest on a correct reading of the API rather than on observed behaviour, which is exactly
the epistemic position that produced bug 2. **These need a play-test before they can be called
fixed.**

Specifically, on a live server with a Java client:

1. Fly deliberately into a dirt wall at default speed. Expect: the carpet stops, no suffocation.
2. Sneak down to the ground. Expect: the carpet stows itself and the rug returns to the off-hand.
3. Deploy under a low ceiling. Expect: it still deploys and still flies.
4. Confirm the deploy lift feels right — 1.2 blocks is a chosen number, not a measured one.
5. While there, settle the two obligations that have never been checked by anyone: whether riders
   actually render seated, and whether the `-0.6` carpet offset puts the rug under them.

## What to do next

1. Reproduce both on a disposable stack with a real Java client attached — gate 7a attaches no
   client, which is exactly why both of these survived it.
2. Confirm the `isOnGround()` behaviour for a passenger before changing anything.
3. Fix, add regression coverage where the logic is separable, and ship as `v0.1.1`.
4. Re-check the remaining unverified client-facing obligations in the same play-test pass —
   above all the `-0.6f` carpet offset and whether riders actually render seated, which is still
   unconfirmed by anyone.

## Second round — Bedrock reports, same day

Five more reports arrived after the fixes above. They resolved into three causes, one of them
architectural.

### Bedrock could not fly at all, and Java could not fly from the main hand

The deploy listener read only the off-hand. **Bedrock clients cannot put a carpet in the off-hand
at all** — the client permits only a fixed set of items there (shields, totems, maps, arrows,
fireworks), and GeyserMC/Geyser#2057 is closed "Can't Fix" because it is a client restriction, not
a Geyser bug. Off-hand-only made carpet flight structurally unreachable on Bedrock. Nobody caught
it because no Bedrock client ever attached to a test stack.

Deploy now accepts either hand, preferring the off-hand so Java behaviour is unchanged, and the
session records which hand it took the rug from so stow, death and quit all return it there.

**The deeper cause was the trigger, not the hand.** Deploy hung off `PlayerJumpEvent`, which Paper
derives from player *movement* (it carries a from/to `Location` pair). Nothing establishes that
Geyser's movement translation produces the pattern that detection expects. Deploy now uses
`PlayerInputEvent`, and that path was verified end to end from source rather than assumed:

- Geyser's `InputCache.processInputs` sets `.withJump(...)` from the Bedrock `JUMP_CURRENT_RAW` /
  `JUMP_DOWN` / `AUTO_JUMPING_IN_WATER` flags and sends `ServerboundPlayerInputPacket`.
- Paper's `ServerGamePacketListenerImpl.handlePlayerInput` fires `PlayerInputEvent` from that
  packet whenever the input changes, with no player-type gating.

The Paper side was checked specifically because if Paper did not fire that event, the change would
have broken deploy for *everyone* rather than fixing it for Bedrock.

### Bedrock saw the rug around a Java rider's neck

Both visuals rode the mount as passengers, so both sat at the vehicle attachment point — roughly
chest height. Only the `BlockDisplay` had a correction (`TRANSLATE_Y = -0.6`, described in its own
Javadoc as an uncalibrated estimate); a `FallingBlock` has no transformation to correct with, so
the Bedrock stand-in got none at all.

Both visuals are now driven to the rider's feet every tick instead of being carried. That removes
the attachment offset from the problem rather than trying to cancel it, makes both editions render
from the same coordinate so they cannot disagree, and deletes the guessed constant outright.
`setTeleportDuration(1)` keeps the display smooth. The Java carpet also shrank from 3x3 to 1x1 to
match the unscalable Bedrock stand-in — which incidentally fixed a centring bug: `-0.5` centres a
1x1 display exactly but left the 3x one centred a full block off the rider.

### Standing is now the default for both editions

`flight.java-mode` now defaults to `standing`. Every serious bug in v0.1.0 traces to one decision —
the rider being a **passenger** so they render seated:

| Bug | Cause |
| --- | --- |
| Suffocated in a dirt wall | Server drives the mount by teleport; raw teleports do not collide |
| Landing never stowed | A passenger's own ground flag never becomes true |
| No dismount | `EntityDismountEvent` must be cancelled or sneak ejects the rider mid-air |
| Rug at neck height | Fighting an uncalibrated passenger attachment offset |

Standing has none of it: the player flies themselves with real collision, is not a passenger, and
has no vehicle to eject from. The cost is real and worth stating — **"flies where you look" is
gone**; standing mode is native flight with ordinary movement keys.

This was a one-line default change rather than a rewrite because both paths plus config were built
at design time. `seated` remains fully supported and selectable.

`flight.speed` still applies to seated mode only. No standing-speed setting was added: `setFlySpeed`
takes an abstract 0.0-1.0 scale, not blocks per tick, and a guessed conversion would be worse than
documenting the gap. Revisit once someone has felt the vanilla speed.

### Verification status

`mvn verify` green at 127 tests (from 107 at v0.1.0); gate 7a green on every change. **None of it
is confirmed in play** — no client has attached, so nothing here has pressed jump, hit a wall,
landed, or looked at a carpet. The visual rewrite is the least verified part of all: it is the only
change whose entire purpose is what a player sees, and it has been seen by nobody.
