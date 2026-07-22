/*
 * MagicCarpet - orchestrator: per-player session registry and the repeating tick loop.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.session;

import io.papermc.paper.entity.TeleportFlag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.xpfarm.magiccarpet.config.FlightModeKind;
import org.xpfarm.magiccarpet.config.MagicCarpetConfig;
import org.xpfarm.magiccarpet.flight.DenyReason;
import org.xpfarm.magiccarpet.flight.FlightGuard;
import org.xpfarm.magiccarpet.flight.FlightMode;
import org.xpfarm.magiccarpet.flight.FuelTank;
import org.xpfarm.magiccarpet.flight.RiderClearance;
import org.xpfarm.magiccarpet.item.CarpetItem;
import org.xpfarm.magiccarpet.visual.CarpetVisual;
import org.xpfarm.magiccarpet.visual.EditionResolver;

/**
 * Owns every rider's {@link CarpetSession} and the single repeating task that drives all of
 * them. This is the integration point for every other component built so far: {@link
 * FlightGuard} decides whether a deploy is allowed, an {@link EditionResolver} plus {@link
 * MagicCarpetConfig} pick a {@link FlightMode} strategy, that strategy spawns the mount, a
 * {@link CarpetVisual} rides along, and a {@link FuelTank} — kept per player for longer than
 * any one session — gates how long the flight can last.
 *
 * <p><strong>Visual host resolution.</strong> {@link CarpetVisual} always needs a live {@link
 * Entity} to attach its two passengers to, and both {@code FlightMode} implementations supply
 * one directly from {@code deploy}: {@code SeatedFlightMode} the ArmorStand mount the player
 * also rides, {@code StandingFlightMode} a dedicated marker ArmorStand anchor that carries only
 * the visual (see that class's Javadoc for why the player itself is never used as the visual
 * host — {@code CarpetVisual}'s translation offset is calibrated for a marker ArmorStand's
 * passenger attachment, not a full-size {@link Player}'s, and the design specifies both visuals
 * as passengers of an ArmorStand). Both modes return {@code null} from {@code deploy} only in
 * the shared degenerate case where the deploy {@link Location} has no {@link
 * org.bukkit.World}; that case never reaches session registration (see {@link #deploy}), so
 * {@link CarpetSession#mount()} is guaranteed non-null for every session that actually exists,
 * for both modes — no {@code mount != null ? mount : player} substitution is needed anywhere
 * in this class.
 *
 * <p><strong>Tick guarding.</strong> The repeating task runs at 1-tick period. Each player's
 * slice of the tick body is wrapped in its own {@code try/catch}: an exception there is logged
 * once with that player's name and ends only their session, exactly as required — a thrown
 * exception escaping a Bukkit repeating task cancels it permanently and silently, which would
 * take carpet flight down for every rider on the server, not just the one who hit the bug.
 */
public final class CarpetManager {

    private static final String USE_PERMISSION = "magiccarpet.use";
    private static final String ORPHAN_TAG = "magiccarpet";
    private static final int TICKS_PER_SECOND = 20;

    /**
     * Minimum fraction of a full tank a deploy requires (see {@link FuelTank#isBelowFraction}).
     * 5%: small enough that it only rejects a deploy when there is genuinely nothing meaningful
     * left to fly on (at the shortest allowed {@code fuel.capacity-seconds} of 5s, that is a
     * quarter of a second of flight — not worth the entity/sound cost of even starting), while
     * still being comfortably above zero so a rider whose tank reads "empty" from accumulated
     * floating-point drain but is a rounding hair above true zero is not waved through only to
     * have {@code FUEL_EMPTY} fire again next tick.
     */
    private static final double MIN_DEPLOY_FUEL_FRACTION = 0.05;

    /**
     * How often {@link #tickSession} re-broadcasts visual visibility to every player in the
     * rider's world (see {@link #refreshVisualViewers}). Once per second (20 ticks), not every
     * tick: {@link CarpetVisual#refreshViewers} is only correcting for players who joined,
     * changed worlds, or otherwise were not in the original {@code deploy}-time snapshot — none
     * of those are frame-perfect events a human could perceive the timing of, so a 1-second
     * worst-case delay before a new arrival sees the rider seated on a visible carpet is
     * unnoticeable, while still being far cheaper than doing this per-viewer show/hide call
     * every single tick for every active session.
     */
    private static final int VIEWER_REFRESH_INTERVAL_TICKS = 20;

    private final Plugin plugin;
    private final EditionResolver editionResolver;
    private final FlightMode seatedFlightMode;
    private final FlightMode standingFlightMode;

    private final Map<UUID, CarpetSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, FuelTank> fuelTanks = new ConcurrentHashMap<>();
    private final Set<UUID> teardownInProgress = ConcurrentHashMap.newKeySet();

    private volatile MagicCarpetConfig config;
    private volatile FlightGuard flightGuard;

    public CarpetManager(
            Plugin plugin,
            MagicCarpetConfig config,
            FlightGuard flightGuard,
            EditionResolver editionResolver,
            FlightMode seatedFlightMode,
            FlightMode standingFlightMode) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.flightGuard = Objects.requireNonNull(flightGuard, "flightGuard");
        this.editionResolver = Objects.requireNonNull(editionResolver, "editionResolver");
        this.seatedFlightMode = Objects.requireNonNull(seatedFlightMode, "seatedFlightMode");
        this.standingFlightMode = Objects.requireNonNull(standingFlightMode, "standingFlightMode");
    }

    /**
     * Replaces the live configuration and the {@link FlightGuard} built from it, for {@code
     * /carpet reload} (task 10).
     *
     * <p>{@code FlightGuard} holds its own immutable config snapshot (task 3), so a config
     * reload cannot be applied by swapping {@link #config} alone — the guard must be rebuilt
     * too. This class does not rebuild it internally because it does not own the {@code
     * RegionQuery} the guard needs (WorldGuard wiring belongs to task 9); the caller that
     * already has both the new config and a freshly built {@code FlightGuard} passes both here
     * together, rather than this method hiding a partial reload behind a config-only signature.
     *
     * <p><strong>Effect on active sessions.</strong> Both fields are read fresh on every use,
     * not captured at deploy time, so already-flying riders pick up the new {@code
     * flight.speed} and {@code flight.altitude-ceiling} on their very next tick, and any
     * deploy attempted after this call uses the new world/region/permission rules immediately.
     * One thing does <em>not</em> change retroactively: a session's {@link CarpetSession#mode()}
     * (the {@code FlightMode} strategy) is fixed at deploy time and does not switch mid-flight
     * even if {@code flight.java-mode}/{@code flight.bedrock-mode} changes.
     *
     * <p><strong>Fuel tanks.</strong> {@link FuelTank} fixes its capacity and recharge rate at
     * construction and has no reconfigure method, so a changed {@code fuel.capacity-seconds} /
     * {@code fuel.recharge-seconds} cannot be applied to an existing {@link FuelTank} instance
     * in place — it must be rebuilt. This method drops every entry from {@link #fuelTanks}
     * <strong>except</strong> the ones backing a player who is flying right now (i.e. present in
     * {@link #sessions}): dropping a grounded player's stale tank is safe, since {@link #deploy}
     * lazily recreates it — from the config now in effect — the next time they fly, exactly as
     * if their entry had never existed. Dropping an <em>active</em> rider's tank mid-flight would
     * be worse than doing nothing: {@link #tick} would call {@code drain}/{@code isEmpty} on a
     * brand-new full-capacity replacement, silently discarding whatever charge they had actually
     * burned so far and handing them a free refill mid-air. So a currently-flying rider's tank is
     * left completely untouched by a reload — same instance, same capacity, same recharge rate,
     * same remaining charge — and only picks up the new fuel config on their <em>next</em>
     * deploy, after landing.
     */
    public void applyConfig(MagicCarpetConfig config, FlightGuard flightGuard) {
        this.config = Objects.requireNonNull(config, "config");
        this.flightGuard = Objects.requireNonNull(flightGuard, "flightGuard");
        fuelTanks.keySet().removeIf(id -> !sessions.containsKey(id));
    }

    /**
     * Attempts to start carpet flight for {@code player}.
     *
     * <p>Order: already-flying guard, then {@link FlightGuard#checkDeploy}, then the fuel gate
     * (below), then mode selection, then {@code mode.deploy}, then the visual. A denial from the
     * guard sends the {@link DenyReason} message and returns {@code false} without touching
     * anything else. A thrown {@link IllegalStateException} from {@code mode.deploy} or from the
     * {@link CarpetVisual} constructor is caught, logged, and reported to the player as a plain
     * failure message; in both cases no session is registered and nothing is left half-built:
     *
     * <ul>
     *   <li>{@code mode.deploy} throwing means no mount was left behind (the strategy classes
     *       clean up their own partial state before throwing — see their Javadoc) and this
     *       method returns before creating a {@link CarpetVisual} at all.
     *   <li>The {@link CarpetVisual} constructor throwing means the mount {@code mode.deploy}
     *       already returned successfully still exists and must be torn down here — this
     *       method calls {@code mode.dismiss(player)} in that catch block before returning.
     *       {@code dismiss} never throws and is idempotent for both modes, so this is safe
     *       even though the mount was never registered in a {@link CarpetSession}.
     * </ul>
     *
     * <p><strong>Fuel gate.</strong> A deploy is refused with a player-visible message when
     * {@code player}'s {@link FuelTank} reads below {@link #MIN_DEPLOY_FUEL_FRACTION} of
     * capacity — this is what stops a rider from jump-spamming a mount/visual spawn (three
     * entities, two sounds, particles) all through a 120-second recharge after their fuel ran
     * out mid-flight. Checked after the {@code FlightGuard} denial (so an out-of-fuel player in
     * a world where they lack permission still sees the permission message, not a fuel one) and
     * before anything is spawned.
     *
     * <p><strong>Off-hand rug.</strong> Once the mount and visual both exist, the exact {@link
     * org.bukkit.inventory.ItemStack} in {@code player}'s off-hand is removed and stored on the
     * new {@link CarpetSession} (see {@link CarpetSession#heldRug()}) — not a freshly created
     * replacement. {@link #endSession} (via {@code returnRug}) hands that same instance back on
     * dismiss, which is what actually closes the deploy/dismiss pair the design specifies;
     * before this, {@code deploy} never touched the off-hand at all and dismiss unconditionally
     * minted a new rug into any empty slot, which duplicated the item every time a rider emptied
     * their off-hand mid-flight and then landed or ran out of fuel.
     *
     * @return {@code true} if a session was started
     */
    public boolean deploy(Player player) {
        Objects.requireNonNull(player, "player");
        UUID id = player.getUniqueId();
        if (sessions.containsKey(id)) {
            // Already flying. This is the guard that actually keeps FlightMode.deploy's own
            // duplicate-session IllegalStateException from ever firing in normal operation —
            // CarpetManager is the only caller of FlightMode.deploy.
            return false;
        }

        Location at = player.getLocation().clone();
        World world = at.getWorld();
        String worldName = world != null ? world.getName() : "";
        boolean hasPermission = player.hasPermission(USE_PERMISSION);

        MagicCarpetConfig activeConfig = this.config;
        FlightGuard activeGuard = this.flightGuard;

        Optional<DenyReason> denial = activeGuard.checkDeploy(
                worldName, at.getBlockX(), at.getBlockY(), at.getBlockZ(), hasPermission);
        if (denial.isPresent()) {
            player.sendMessage(denial.get().message());
            return false;
        }

        // Fuel gate: created lazily here (not only after a successful deploy) so the very first
        // check a brand-new flier's tank ever gets is this one, at full capacity, which always
        // passes. Checked before any mount/visual is spawned so a rider spamming jump on an
        // empty tank (no combat grace after FUEL_EMPTY, see DenyReason's siblings) does not pay
        // for three entities, two sounds, and particles on every keypress for the whole
        // recharge window — see FuelTank.isBelowFraction's Javadoc for the threshold.
        FuelTank fuelTank = fuelTanks.computeIfAbsent(id, unused -> new FuelTank(
                secondsToTicks(activeConfig.fuelCapacitySeconds()),
                secondsToTicks(activeConfig.fuelRechargeSeconds())));
        if (fuelTank.isBelowFraction(MIN_DEPLOY_FUEL_FRACTION)) {
            player.sendMessage(
                    "Your carpet is out of fuel and is still recharging. Try again once it has refilled.");
            return false;
        }

        FlightMode mode = resolveMode(player, activeConfig);

        Entity mount;
        try {
            mount = mode.deploy(player, at);
        } catch (IllegalStateException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Carpet flight failed to deploy for " + player.getName()
                            + ": the flight mode rejected the deploy (see cause)", e);
            player.sendMessage("Your carpet failed to unfurl. Try again.");
            return false;
        }

        CarpetVisual visual;
        try {
            visual = new CarpetVisual(plugin, editionResolver, mount);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Carpet visual failed to spawn for " + player.getName()
                            + "; rolling back the flight mount that was already created", e);
            // The visual failed, but the mount (if any) is real and must not be leaked: tear it
            // down through the mode that owns it. dismiss() never throws and is idempotent.
            mode.dismiss(player);
            player.sendMessage("Your carpet failed to unfurl. Try again.");
            return false;
        }

        // Close the deploy/dismiss pair: take the exact rug stack out of the off-hand now that
        // the flight has actually started, and hold onto that same instance for the whole
        // session. Deploy is only ever reached via CarpetListeners.onPlayerJump, which already
        // verified the off-hand holds a carpet before calling this method — but this class does
        // not re-check that itself (deploy() is public), so a defensive empty/air fallback is
        // used if the slot is somehow already empty rather than assuming a carpet is there.
        // Never re-synthesizes a new CarpetItem.create() here or on dismiss: see
        // returnRug/peekHeldRug for why fabricating a replacement is exactly the bug this fixes.
        ItemStack heldRug = player.getInventory().getItemInOffHand().clone();
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));

        CarpetSession session =
                new CarpetSession(id, mode, mount, visual, fuelTank, at.getBlockY(), heldRug);
        sessions.put(id, session);

        if (world != null) {
            visual.refreshViewers(new ArrayList<>(world.getPlayers()));
        }
        try {
            playDeployEffects(activeConfig, at);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to play carpet deploy effects for " + player.getName()
                            + "; the session was still started successfully", e);
        }
        return true;
    }

    private FlightMode resolveMode(Player player, MagicCarpetConfig activeConfig) {
        FlightModeKind kind = editionResolver.isBedrock(player)
                ? activeConfig.flightBedrockMode()
                : activeConfig.flightJavaMode();
        return kind == FlightModeKind.SEATED ? seatedFlightMode : standingFlightMode;
    }

    /**
     * Ends {@code player}'s carpet flight, if any. Idempotent: a player with no active session
     * is a no-op. Tears down the visual and the mount (via the owning {@code FlightMode}),
     * restores the rug to the off-hand, and plays the stow effects.
     *
     * <p>Deliberately does not vary its teardown behaviour by {@code cause} beyond logging —
     * in particular, for {@link DismissCause#FUEL_EMPTY} this method does not touch the
     * player's velocity, position, or fall state in any way. It only removes the visual and
     * the mount; whatever gravity/falling the player is already subject to at that instant
     * continues completely untouched, which is what lets fall damage happen normally.
     */
    public void dismiss(Player player, DismissCause cause) {
        if (player == null) {
            return;
        }
        CarpetSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        endSession(player, session, cause);
    }

    /**
     * Whether {@code player} currently has an active carpet flight session in this manager.
     *
     * <p><strong>Not the same thing as {@link Player#isFlying()}.</strong> Bukkit's {@code
     * Player#isFlying()} is the vanilla flight flag (creative/spectator flight, or an
     * {@code allowFlight} grant) and has nothing to do with carpets — a creative-mode player can
     * have {@code isFlying()} return {@code true} with no carpet session at all, and a seated
     * carpet rider (see {@code SeatedFlightMode}) has an active session here while {@code
     * player.isFlying()} is {@code false}, since they are riding a mount rather than using
     * vanilla flight. This method answers a different question — "does {@code player} have a
     * live {@link CarpetSession} in {@link #sessions}" — and is named {@code hasActiveSession}
     * rather than reusing {@code isFlying} specifically to avoid that collision.
     *
     * <p>Added for task 8's {@code /carpet off}, which needs to tell a rider with nothing to
     * stow ("you have no active carpet") apart from an actual dismiss ("your carpet has been
     * stowed") — {@link #dismiss} is {@code void} and intentionally silent for the no-session
     * case, so it cannot answer that question on its own. Purely additive: does not change the
     * signature or behaviour of any existing method.
     */
    public boolean hasActiveSession(Player player) {
        if (player == null) {
            return false;
        }
        return sessions.containsKey(player.getUniqueId());
    }

    /**
     * Whether {@code player}'s session is, at this exact moment, being torn down by this
     * manager's own {@link #endSession} — specifically while {@code FlightMode.dismiss} is
     * removing the mount/anchor entity, which can itself trigger a Bukkit {@code
     * EntityDismountEvent} for the rider.
     *
     * <p>Added for task 9's dismount listener, which must cancel any dismount this manager did
     * not initiate (see {@link #endSession}'s comment on the call this flag brackets) while
     * letting this manager's own teardown-triggered dismount pass through uncancelled. Originally
     * documented as "the one authorized touch to this class from task 9's brief" — task 9's fix
     * pass added a second, purely additive accessor, {@link #activeSessionEntityOwners()}, for
     * the same reason: both are read-only additions {@code CarpetListeners} needs and neither
     * changes the signature or behaviour of any pre-existing method.
     */
    public boolean isTeardownInProgress(Player player) {
        return player != null && teardownInProgress.contains(player.getUniqueId());
    }

    /**
     * A snapshot mapping every entity UUID that belongs to a currently active session — the
     * mount/anchor ({@link CarpetSession#mount()}) plus both {@link CarpetVisual} passenger
     * entities ({@link CarpetSession#visual()}'s {@code entityIds()}) — to the UUID of the
     * player whose session it is. Empty (never {@code null}) when no session is active, which
     * is the overwhelmingly common case: most {@link org.bukkit.event.world.ChunkUnloadEvent}s
     * server-wide fire with nobody flying at all.
     *
     * <p>Added for task 9's fix pass, replacing {@code CarpetListeners.onChunkUnload}'s original
     * location-coincidence proxy (matching a rider's own current chunk against the unloading
     * chunk) with an exact identity check: that listener calls this once per {@code
     * ChunkUnloadEvent} — not once per session and not once per candidate entity — checks it for
     * emptiness as its own cheap early-out before ever touching {@link
     * org.bukkit.Chunk#getEntities()}, and then does an O(1) {@code Map.get} per entity actually
     * found in the unloading chunk. Built fresh from {@link #sessions} on every call rather than
     * maintained incrementally, since sessions start and end continuously and this is cheap
     * relative to a chunk unload (bounded by the number of concurrently flying riders, not by
     * chunk or world size). Read-only: does not touch {@link #sessions} or any other state.
     */
    public Map<UUID, UUID> activeSessionEntityOwners() {
        if (sessions.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> owners = new HashMap<>();
        for (CarpetSession session : sessions.values()) {
            UUID playerId = session.playerId();
            Entity mount = session.mount();
            if (mount != null) {
                owners.put(mount.getUniqueId(), playerId);
            }
            for (UUID visualId : session.visual().entityIds()) {
                owners.put(visualId, playerId);
            }
        }
        return owners;
    }

    private void endSession(Player player, CarpetSession session, DismissCause cause) {
        // Task 9 addition: FlightMode.dismiss() below removes the mount/anchor entity, which
        // can itself raise a Bukkit EntityDismountEvent for player (the rider being removed
        // from a vehicle that just disappeared). CarpetListeners must cancel any dismount it
        // did NOT ask for (a sneaking rider would otherwise be ejected mid-flight, when sneak
        // is supposed to mean "descend, stay seated") while letting exactly this one through
        // uncancelled. isTeardownInProgress(player) is how it tells the two apart. Scoped to
        // this one call only, and always cleared via finally even if dismiss() itself throws
        // (it is documented not to, but this must not leave the flag stuck regardless).
        UUID id = player.getUniqueId();
        teardownInProgress.add(id);
        try {
            session.mode().dismiss(player); // documented not to throw, but caught below regardless
        } catch (RuntimeException e) {
            // session was already removed from `sessions` by the caller (dismiss()/tick()/
            // shutdownAll()) before endSession runs, so this session's CarpetSession — and the
            // player's rug ItemStack it alone holds — has no other owner. Letting this exception
            // propagate would skip visual.remove() and returnRug() below, leaking the visual
            // entities and destroying the rug outright rather than merely losing track of a
            // mount. Log and fall through instead so both still run.
            plugin.getLogger().log(Level.WARNING,
                    "Failed to dismiss carpet flight mode for " + player.getName()
                            + " while dismissing (" + cause + "); continuing teardown", e);
        } finally {
            // Cleared here unconditionally (success, caught exception, or any other path out of
            // this try) so a thrown dismiss() can never leave this flag stuck — see this flag's
            // Javadoc for why a stuck flag would make every later dismount for this player
            // permanently uncancellable.
            teardownInProgress.remove(id);
        }
        try {
            session.visual().remove();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to remove carpet visual for " + player.getName()
                            + " while dismissing (" + cause + ")", e);
        }
        returnRug(player, session, cause);
        try {
            playStowEffects(this.config, player.getLocation());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to play carpet stow effects for " + player.getName()
                            + " while dismissing (" + cause + ")", e);
        }
    }

    /**
     * Gives back the exact rug {@link ItemStack} {@link #deploy} took out of {@code player}'s
     * off-hand — never a freshly minted {@link CarpetItem#create()} replacement, which is what
     * let a rug be duplicated: {@code deploy} never removed the original in the first place, so
     * every dismiss minted a brand-new one regardless of whether the player still had the
     * physical item. Now that {@code deploy} takes the rug and holds the exact instance on the
     * {@link CarpetSession}, this is the other half of that pair, and it hands back that same
     * instance every time — so any durability, NBT, or renamed-in-an-anvil state the physical
     * item picked up survives the flight.
     *
     * <p>A no-op if {@link CarpetSession#heldRug()} is air/empty — the defensive case where the
     * off-hand was already empty at deploy time (see that field's Javadoc), meaning there is
     * nothing to give back and nothing was ever taken.
     *
     * <p><strong>{@link DismissCause#DEATH} is deliberately skipped here.</strong> {@link
     * CarpetListeners#onPlayerDeath} calls {@link #peekHeldRug} <em>before</em> calling {@link
     * #dismiss}, then decides where the rug goes based on {@code PlayerDeathEvent.getKeepInventory()}
     * — a value only that event exposes, which this class has no access to. If this method also
     * tried to hand the rug back for a {@code DEATH} dismiss, the rug would be given out twice
     * (once here, once by the listener) for every death.
     *
     * <p><strong>{@link DismissCause#QUIT} writes to the off-hand like every other cause.</strong>
     * CraftBukkit's {@code PlayerList.remove()} fires {@code PlayerQuitEvent} and only afterward
     * calls {@code save(entityplayer)}, so an inventory write made while handling the quit event
     * (which is where this whole dismiss chain runs from) persists in the save that follows — the
     * same basis every save-inventory-on-quit plugin relies on. The off-hand is also guaranteed
     * empty at this point: {@link #deploy} set it to {@code AIR} when it took the rug, and nothing
     * else can have put something else there for a player who is disconnecting. So
     * {@code setItemInOffHand} below cannot overwrite anything for this cause, and there is no
     * reason to fall back to a world drop — which would otherwise cost the rug to despawn timers,
     * the void, lava, or a bystander picking it up, for what is the single most common abnormal
     * end of a flight.
     */
    private void returnRug(Player player, CarpetSession session, DismissCause cause) {
        ItemStack rug = session.heldRug();
        if (rug.getType() == Material.AIR || rug.getAmount() <= 0) {
            return; // nothing was taken at deploy time; nothing to give back.
        }
        if (cause == DismissCause.DEATH) {
            return; // CarpetListeners.onPlayerDeath handles this cause itself; see method doc.
        }
        try {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (offHand == null || offHand.getType() == Material.AIR) {
                player.getInventory().setItemInOffHand(rug);
            } else {
                dropRugAtFeet(player, rug);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to return the carpet rug to " + player.getName()
                            + " while dismissing (" + cause + ")", e);
        }
    }

    private static void dropRugAtFeet(Player player, ItemStack rug) {
        World world = player.getWorld();
        if (world != null) {
            world.dropItemNaturally(player.getLocation(), rug);
        }
    }

    /**
     * A defensive copy of the rug {@link ItemStack} held by {@code player}'s currently active
     * session (see {@link CarpetSession#heldRug()}), or {@code null} if there is no active
     * session or nothing was taken at deploy time.
     *
     * <p>Added so {@link CarpetListeners#onPlayerDeath} can retrieve the exact item before
     * calling {@link #dismiss} — {@code dismiss} removes the session (and, for every other
     * cause, would hand the rug straight back via {@link #returnRug}), but a death needs the
     * item routed through {@code PlayerDeathEvent} itself: added to the off-hand directly when
     * {@code event.getKeepInventory()} is {@code true}, or to {@code event.getDrops()} when it
     * is {@code false}, so it participates correctly in whichever the death is already doing to
     * the rest of the inventory instead of being written to a slot that is about to be cleared.
     * Read-only: does not remove or alter anything this class tracks.
     */
    public ItemStack peekHeldRug(Player player) {
        if (player == null) {
            return null;
        }
        CarpetSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return null;
        }
        ItemStack rug = session.heldRug();
        if (rug.getType() == Material.AIR || rug.getAmount() <= 0) {
            return null;
        }
        return rug.clone();
    }

    /**
     * Drops the persisted per-player {@link FuelTank}. Task 9's quit listener is expected to
     * call this in addition to, not instead of, {@link #dismiss} with {@link
     * DismissCause#QUIT} — a player can quit with no active session (having flown and landed
     * earlier in the same server run) and still needs their leftover charge cleared so a
     * rejoin starts fresh, exactly as the fuel gauge is specified to do.
     */
    public void clearFuelTank(Player player) {
        if (player == null) {
            return;
        }
        fuelTanks.remove(player.getUniqueId());
    }

    /**
     * Advances every active session by one tick, then recharges fuel for grounded players who
     * are not currently flying. Meant to be registered on a 1-tick repeating task.
     *
     * <p>Each session's slice of this method runs inside its own {@code try/catch}: an
     * exception there is logged once with that player's name and ends only that one session
     * (via {@link DismissCause#ERROR} — see that constant's Javadoc for why), never
     * propagating out of this method. An uncaught exception escaping a Bukkit repeating task
     * silently cancels the task forever, which would end carpet flight for every rider on the
     * server, not just the one whose tick threw. The recovery {@link #dismiss} call itself is
     * also guarded: teardown code can throw too (see {@link #endSession}'s callers), and a
     * second exception from the recovery path must not be allowed to escape this method either
     * — it falls back to {@link #forceRemoveSession}, which only touches raw entity references
     * and cannot itself depend on the teardown path that just failed.
     */
    public void tick() {
        for (Map.Entry<UUID, CarpetSession> entry : sessions.entrySet()) {
            UUID id = entry.getKey();
            CarpetSession session = entry.getValue();
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                // No listener has torn this down yet (e.g. a quit is still being processed).
                // Leave it for the next tick or the quit/shutdown path rather than guessing.
                continue;
            }
            try {
                tickSession(player, session);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Unhandled exception while ticking carpet flight for " + player.getName()
                                + "; ending their session", e);
                try {
                    dismiss(player, DismissCause.ERROR);
                } catch (RuntimeException dismissFailure) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to cleanly end carpet flight for " + player.getName()
                                    + " after an earlier tick exception; forcing entity removal directly",
                            dismissFailure);
                    forceRemoveSession(id, session);
                }
            }
        }

        for (Map.Entry<UUID, FuelTank> entry : fuelTanks.entrySet()) {
            UUID id = entry.getKey();
            if (sessions.containsKey(id)) {
                continue; // airborne: fuel drains, it does not recharge
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            try {
                if (RiderClearance.isGrounded(player)) {
                    entry.getValue().recharge(1);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Unhandled exception while recharging carpet fuel for " + player.getName(), e);
            }
        }
    }

    /**
     * One player's tick body. Order matches the brief exactly: poll input once, delegate to
     * the flight mode, drain fuel, then either end the session (ground contact or fuel
     * exhaustion — see {@link SessionTickOutcome} for the tie-break between the two) or, if
     * flight continues, enforce the altitude ceiling.
     *
     * <p><strong>Ground detection.</strong> Uses {@link RiderClearance#isGrounded}, which probes
     * a thin slab beneath the rider's own hitbox, for both flight modes.
     *
     * <p>This replaced {@code player.isOnGround()}, which did not work. A seated rider is a
     * <em>passenger</em>, and a passenger's own ground flag does not track ground contact — the
     * vehicle carries that state — so it stayed false for the whole ride, {@code LANDED} never
     * fired, and the carpet never auto-stowed. Confirmed in play by the first Java player to fly
     * one: they touched down and stayed seated, with no way off short of running the fuel dry.
     * That risk was recorded pre-release as this gate's "top risk" with the wording "may misfire";
     * it did not misfire, it never fired. A geometric probe is correct for a passenger, for a
     * natively-flying player, and for a player on foot alike, because it asks about the world
     * rather than about a movement flag whose meaning depends on what the player is riding.
     *
     * <p>Landing is armed by {@link CarpetSession#hasBeenAirborne()} — see that field for why a
     * rider who has never left the ground cannot land.
     */
    private void tickSession(Player player, CarpetSession session) {
        Input input = player.getCurrentInput();
        session.mode().tick(player, input, this.config);

        if (Bukkit.getServer().getCurrentTick() % VIEWER_REFRESH_INTERVAL_TICKS == 0) {
            refreshVisualViewers(player, session);
        }

        session.fuelTank().drain(1);
        boolean fuelEmpty = session.fuelTank().isEmpty();
        boolean grounded = RiderClearance.isGrounded(player);
        if (!grounded) {
            session.markAirborne();
        }

        SessionTickOutcome.EndReason reason =
                SessionTickOutcome.decide(grounded, fuelEmpty, session.hasBeenAirborne());
        switch (reason) {
            case LANDED -> dismiss(player, DismissCause.LANDED);
            case FUEL_EMPTY -> dismiss(player, DismissCause.FUEL_EMPTY);
            case NONE -> applyAltitudeCeiling(player, session);
        }
    }

    /**
     * Re-applies {@link CarpetVisual#refreshViewers}, showing the rider's carpet to every
     * player currently in their world. {@link #deploy} only calls this once, against the
     * world's player list at that exact instant — anyone who joins the server or changes worlds
     * afterward is never in that snapshot and, since both visuals are spawned {@code
     * setVisibleByDefault(false)}, sees the rider seated on nothing for the rest of the flight
     * unless something calls this again. {@link #tickSession} does, on the cadence documented
     * on {@link #VIEWER_REFRESH_INTERVAL_TICKS}. A no-op if the player's world is somehow
     * {@code null} (a degenerate {@link Location}, already excluded from ever reaching a live
     * session — see {@link #deploy} — but {@code Player#getWorld()} is checked defensively here
     * all the same, matching every other {@code World}-typed read in this class).
     */
    private void refreshVisualViewers(Player player, CarpetSession session) {
        World world = player.getWorld();
        if (world != null) {
            session.visual().refreshViewers(new ArrayList<>(world.getPlayers()));
        }
    }

    /**
     * Clamps the flying entity to {@code FlightGuard.clampAltitude}'s ceiling, teleporting it
     * down if it climbed above that. Always uses {@code RETAIN_PASSENGERS}: in seated mode that
     * keeps the player seated through the correction.
     *
     * <p><strong>Which entity is "flying" is mode-dependent, and — since both modes now return
     * a non-null entity from {@code deploy} — is decided by comparing {@link
     * CarpetSession#mode()} against this manager's own {@code seatedFlightMode} field, not by
     * a {@code mount != null} check.</strong> In seated mode the mount carries the player as
     * its passenger, so clamping the mount's Y also moves the player; the player is never
     * clamped directly. In standing mode the opposite is true: the player is client-driven and
     * carries no one, so the player is clamped directly, and the visual anchor — which carries
     * no gameplay state, only the {@link CarpetVisual} passengers — is deliberately left alone
     * here. {@code StandingFlightMode.tick} re-teleports the anchor to the player's (now
     * corrected) location on the very next tick, so a clamp lags the visual by at most one tick;
     * clamping the anchor instead would leave the player themself above the ceiling while only
     * the decoration snapped down, which is the actual bug this comparison avoids.
     *
     * <p><strong>Ground base is resampled every tick, not the deploy-time snapshot.</strong>
     * {@code config.yml} documents {@code flight.altitude-ceiling} as "blocks above terrain",
     * but {@link CarpetSession#groundY()} is captured once, at deploy. Passing that stale value
     * into every later tick's clamp would hard-lock the ceiling to wherever the rider happened
     * to take off — flying from a valley into a mountain would clamp them below the mountain's
     * own surface, phasing them into it instead of letting them climb over it, since a raw
     * teleport (see {@link #applyAltitudeCeiling}'s own use and {@link SeatedFlightMode#tick})
     * never collides. {@link #terrainHeightAt} samples the world's highest solid block directly
     * beneath the flyer's current column instead, so the ceiling — and the floor {@code
     * FlightGuard.clampAltitude} also enforces via its {@code Math.max(groundY, ...)} — both
     * track the terrain actually underneath the rider on every tick. {@link
     * CarpetSession#groundY()} is kept only as the fallback for the (never expected in practice)
     * case where the flyer's current {@link Location} has no {@link World} to sample.
     */
    @SuppressWarnings("deprecation") // TeleportFlag.EntityState: see SeatedFlightMode's note.
    private void applyAltitudeCeiling(Player player, CarpetSession session) {
        Entity flyer = session.mode() == seatedFlightMode ? session.mount() : player;
        if (flyer.isDead() || !flyer.isValid()) {
            return;
        }
        Location current = flyer.getLocation();
        World world = current.getWorld();
        int groundY = world != null
                ? terrainHeightAt(world, current.getBlockX(), current.getBlockZ())
                : session.groundY();
        int clampedY = this.flightGuard.clampAltitude(current.getBlockY(), groundY);
        if (clampedY < current.getBlockY()) {
            Location clamped = current.clone();
            clamped.setY(clampedY);
            flyer.teleport(clamped, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
    }

    /**
     * The block Y directly above the highest solid block at {@code (x, z)} in {@code world} —
     * i.e. the terrain surface a player standing at that column would be standing on. One block
     * lookup, no raycast: cheap enough to run every tick for every active session, unlike a
     * multi-sample or ray-marched terrain probe would be. Uses {@link
     * World#getHighestBlockAt(int, int)} (verified present on {@code paper-api
     * 26.1.2.build.74-stable} via {@code javap}; there is no {@code getHighestBlockYAt} on this
     * API version, only the {@link org.bukkit.block.Block}-returning form), which is exactly
     * the same "highest non-air block in this column" query every other terrain-height use in
     * the Bukkit ecosystem is built on.
     */
    private static int terrainHeightAt(World world, int x, int z) {
        return world.getHighestBlockAt(x, z).getY() + 1;
    }

    /**
     * Dismisses every active session, for {@code onDisable()}. Prefers the normal {@link
     * #dismiss} path (which needs a live {@link Player} to look up each {@code FlightMode}'s
     * own internal bookkeeping); falls back to removing the session's own entity references
     * directly for the — expected to be unreachable in practice — case where {@link
     * Bukkit#getPlayer(UUID)} already returns {@code null} during shutdown.
     *
     * <p>The {@link #dismiss} call is itself guarded per session: teardown code (the visual's
     * removal, the stow effects, and the {@code FlightMode}'s own dismiss) can throw despite
     * best efforts elsewhere to prevent it, and one bad session must not abort this loop and
     * leak every remaining rider's mount and visual — the exact moment cleanup matters most,
     * since nothing will sweep them until the next {@code onEnable()}. A session whose normal
     * {@link #dismiss} throws falls back to {@link #forceRemoveSession} for that one session
     * only, and the loop continues to the next.
     */
    public void shutdownAll() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            CarpetSession session = sessions.get(id);
            if (session == null) {
                continue; // already torn down concurrently
            }
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                try {
                    dismiss(player, DismissCause.SHUTDOWN);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to cleanly dismiss carpet flight for " + player.getName()
                                    + " during shutdown; forcing entity removal directly", e);
                    forceRemoveSession(id, session);
                }
            } else {
                forceRemoveSession(id, session);
            }
        }
    }

    /**
     * Removes a session's entities directly, without going through the owning {@code
     * FlightMode}, for the case where no live {@link Player} is available to look one up by.
     * The owning mode's own internal map may keep a stale entry pointing at the now-removed
     * entity; both flight modes already guard every use of their tracked entity with an
     * {@code isDead()}/{@code isValid()} check, so a stale entry is inert rather than a leak.
     *
     * <p>No live {@link Player} means no inventory to write the held rug back into (see {@link
     * #returnRug}'s reasoning for why even a technically-online {@code QUIT} avoids that). It is
     * instead dropped in the world at the mount/anchor's last known location before that entity
     * is removed, so this genuinely-offline path still never destroys it outright — it is left
     * for whoever (or nothing) is standing there rather than vanishing without a trace.
     */
    private void forceRemoveSession(UUID id, CarpetSession session) {
        sessions.remove(id);
        try {
            session.visual().remove();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to remove carpet visual during shutdown for offline player " + id, e);
        }
        Entity mount = session.mount();
        if (mount != null) {
            dropRugAtEntityLocation(mount, session.heldRug(), id);
            try {
                if (!mount.isDead()) {
                    mount.remove();
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to remove carpet mount during shutdown for offline player " + id, e);
            }
        }
    }

    /**
     * Drops {@code rug} in the world at {@code entity}'s current location, for the offline-player
     * fallback in {@link #forceRemoveSession} where there is no {@link Player} to hand it to
     * directly. A no-op for an air/empty stack (nothing was taken at deploy time), a dead/invalid
     * {@code entity} (no location left to drop it at), or a degenerate {@code null} world.
     */
    private void dropRugAtEntityLocation(Entity entity, ItemStack rug, UUID playerId) {
        if (rug.getType() == Material.AIR || rug.getAmount() <= 0) {
            return;
        }
        if (entity.isDead() || !entity.isValid()) {
            return;
        }
        try {
            Location location = entity.getLocation();
            World world = location.getWorld();
            if (world != null) {
                world.dropItemNaturally(location, rug);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to drop the carpet rug during shutdown for offline player " + playerId, e);
        }
    }

    /**
     * Scans every loaded world for entities carrying the {@code magiccarpet} scoreboard tag,
     * skips any that belong to a currently active session, and removes the rest — returning
     * how many were actually removed (an entity whose {@code remove()} itself throws is logged
     * and does not count, and does not stop the sweep). Meant to be called once from {@code
     * onEnable()}, after any previous run of the plugin may have crashed mid-flight and left
     * mounts or visuals behind with nothing left tracking them.
     *
     * <p>In ordinary operation there is no live session yet when this runs (it is an {@code
     * onEnable()}-time call, before any player has deployed a carpet this server run), so the
     * active-session check below is a defensive guard against a future caller invoking this
     * outside that window, not a case exercised today: without it, calling this while a rider's
     * mount or visual happens to still be tagged would strip a live carpet out from under them.
     */
    public int sweepOrphans() {
        Set<UUID> protectedIds = new HashSet<>();
        for (CarpetSession session : sessions.values()) {
            Entity mount = session.mount();
            if (mount != null) {
                protectedIds.add(mount.getUniqueId());
            }
            protectedIds.addAll(session.visual().entityIds());
        }

        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!entity.getScoreboardTags().contains(ORPHAN_TAG)) {
                    continue;
                }
                if (protectedIds.contains(entity.getUniqueId())) {
                    continue; // belongs to a live session; not an orphan
                }
                try {
                    entity.remove();
                    removed++;
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to remove orphaned carpet entity " + entity.getUniqueId(), e);
                }
            }
        }
        return removed;
    }

    private void playDeployEffects(MagicCarpetConfig activeConfig, Location at) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        if (activeConfig.effectsSound()) {
            world.playSound(at, Sound.BLOCK_WOOL_PLACE, 1f, 1.4f);
        }
        if (activeConfig.effectsParticles()) {
            world.spawnParticle(Particle.CLOUD, at, 20, 0.5, 0.2, 0.5, 0.01);
        }
    }

    private void playStowEffects(MagicCarpetConfig activeConfig, Location at) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        if (activeConfig.effectsSound()) {
            world.playSound(at, Sound.BLOCK_WOOL_BREAK, 1f, 0.8f);
        }
        if (activeConfig.effectsParticles()) {
            world.spawnParticle(Particle.CLOUD, at, 12, 0.5, 0.2, 0.5, 0.01);
        }
    }

    private static int secondsToTicks(int seconds) {
        return seconds * TICKS_PER_SECOND;
    }

    /** Why a carpet flight session ended. */
    public enum DismissCause {
        /** Ground contact — the normal, damage-free way a flight ends. */
        LANDED,
        /** The fuel gauge ran out while airborne; the rider falls and takes fall damage. */
        FUEL_EMPTY,
        /** Taking damage forced a descent (task 8/9's combat listener). */
        COMBAT,
        /** An admin or the player themself ended it via {@code /carpet off}. */
        COMMAND,
        /** The player disconnected while flying. */
        QUIT,
        /** The player died while flying. */
        DEATH,
        /**
         * {@link CarpetManager#shutdownAll()} tore down every session because the plugin is
         * disabling. Distinct from {@link #ERROR}: this cause means the whole server or plugin
         * is going down, not that one rider's session hit a bug — do not conflate the two in a
         * message or metric.
         */
        SHUTDOWN,
        /**
         * The tick loop's own per-player guard forcibly ended this one session after catching
         * an unexpected exception from it (see {@link CarpetManager#tick()}). Distinct from
         * {@link #SHUTDOWN}: this is an isolated bug affecting one rider, not the plugin or
         * server shutting down, and a future message or metric must not report it as such.
         */
        ERROR
    }
}
