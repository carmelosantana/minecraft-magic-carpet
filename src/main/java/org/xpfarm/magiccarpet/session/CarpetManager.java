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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * Entity} to attach its two passengers to. {@code SeatedFlightMode.deploy} supplies one (the
 * ArmorStand mount); {@code StandingFlightMode.deploy} returns {@code null} by design — it has
 * no mount, because the whole point of that mode is that the client flies the player itself.
 * In that case this class attaches the visual to the {@link Player} directly: a player is a
 * plain {@link Entity} like any other and can carry passengers, and doing so satisfies the
 * design's "the visual tracking the player" requirement in standing mode for free, since the
 * visual then simply moves with whatever entity carries it — no per-tick teleport of a
 * separate anchor entity is needed. {@link CarpetSession#mount()} still stores exactly what
 * {@code FlightMode.deploy} returned (nullable, as documented there); the player-as-visual-host
 * substitution is a local decision made only where a visual host is needed, not persisted as
 * the session's mount.
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

    private final Plugin plugin;
    private final EditionResolver editionResolver;
    private final FlightMode seatedFlightMode;
    private final FlightMode standingFlightMode;

    private final Map<UUID, CarpetSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, FuelTank> fuelTanks = new ConcurrentHashMap<>();

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
     * Two things do <em>not</em> change retroactively: a session's {@link CarpetSession#mode()}
     * (the {@code FlightMode} strategy) is fixed at deploy time and does not switch mid-flight
     * even if {@code flight.java-mode}/{@code flight.bedrock-mode} changes; and every {@link
     * FuelTank} — both the ones backing an active session and the ones sitting idle in the
     * per-player map from an earlier flight this server run — keeps the capacity and recharge
     * rate it was constructed with, because {@link FuelTank} has no reconfigure method. A
     * changed {@code fuel.capacity-seconds}/{@code fuel.recharge-seconds} only takes effect for
     * a player whose {@link FuelTank} has not been created yet, i.e. someone who has not
     * deployed a carpet since the last time their entry was cleared (see {@link
     * #clearFuelTank(Player)}) or since server start.
     */
    public void applyConfig(MagicCarpetConfig config, FlightGuard flightGuard) {
        this.config = Objects.requireNonNull(config, "config");
        this.flightGuard = Objects.requireNonNull(flightGuard, "flightGuard");
    }

    /**
     * Attempts to start carpet flight for {@code player}.
     *
     * <p>Order: already-flying guard, then {@link FlightGuard#checkDeploy}, then mode
     * selection, then {@code mode.deploy}, then the visual. A denial from the guard sends the
     * {@link DenyReason} message and returns {@code false} without touching anything else. A
     * thrown {@link IllegalStateException} from {@code mode.deploy} or from the {@link
     * CarpetVisual} constructor is caught, logged, and reported to the player as a plain
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

        Entity visualHost = mount != null ? mount : player;
        CarpetVisual visual;
        try {
            visual = new CarpetVisual(plugin, editionResolver, visualHost);
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

        FuelTank fuelTank = fuelTanks.computeIfAbsent(id, unused -> new FuelTank(
                secondsToTicks(activeConfig.fuelCapacitySeconds()),
                secondsToTicks(activeConfig.fuelRechargeSeconds())));

        CarpetSession session = new CarpetSession(id, mode, mount, visual, fuelTank, at.getBlockY());
        sessions.put(id, session);

        if (world != null) {
            visual.refreshViewers(new ArrayList<>(world.getPlayers()));
        }
        playDeployEffects(activeConfig, at);
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

    private void endSession(Player player, CarpetSession session, DismissCause cause) {
        session.mode().dismiss(player); // never throws; idempotent for both modes
        try {
            session.visual().remove();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to remove carpet visual for " + player.getName()
                            + " while dismissing (" + cause + ")", e);
        }
        restoreOffHandIfEmpty(player);
        playStowEffects(this.config, player.getLocation());
    }

    /**
     * Puts a fresh carpet rug in {@code player}'s off-hand if it is currently empty.
     *
     * <p>Nothing in this class ever removes the rug from the off-hand — {@link #deploy} never
     * touches the player's inventory at all, so in the ordinary case the off-hand already
     * holds the rug and this is a no-op. It exists as a defensive guarantee that "the rug
     * returns to the off-hand" is concretely true after every dismiss, in case something else
     * (another plugin, an inventory click, a future consumable mechanic) cleared that slot
     * during the flight. It only fills an <em>empty</em> slot: if the player swapped something
     * else into their off-hand mid-flight, that item is left alone rather than overwritten.
     */
    private void restoreOffHandIfEmpty(Player player) {
        try {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (offHand == null || offHand.getType() == Material.AIR) {
                player.getInventory().setItemInOffHand(CarpetItem.create());
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to restore the carpet rug to the off-hand for " + player.getName(), e);
        }
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
     * (via {@link DismissCause#SHUTDOWN} — see that constant's Javadoc for why), never
     * propagating out of this method. An uncaught exception escaping a Bukkit repeating task
     * silently cancels the task forever, which would end carpet flight for every rider on the
     * server, not just the one whose tick threw.
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
                dismiss(player, DismissCause.SHUTDOWN);
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
                if (player.isOnGround()) {
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
     * <p><strong>Ground detection.</strong> Uses {@code player.isOnGround()} — the player's
     * own flag, not the mount's — for both flight modes. This is a deliberate, documented
     * choice, not an oversight; the full reasoning and its known weaknesses are recorded in
     * this task's report, since they cannot be fully settled without runtime verification
     * against a live server. In short: {@code isOnGround()} carries vanilla's well-known
     * "block edge" false-positive/negative behaviour in general, and for {@code
     * SeatedFlightMode} specifically the mount is a zero-hitbox marker {@code ArmorStand}
     * moved exclusively by teleport rather than physics, so its own {@code isOnGround()} would
     * very likely never be reliable — which is exactly why the player's own flag is used
     * instead of the mount's, even in seated mode.
     */
    private void tickSession(Player player, CarpetSession session) {
        Input input = player.getCurrentInput();
        session.mode().tick(player, input, this.config);

        session.fuelTank().drain(1);
        boolean fuelEmpty = session.fuelTank().isEmpty();
        boolean grounded = player.isOnGround();

        SessionTickOutcome.EndReason reason = SessionTickOutcome.decide(grounded, fuelEmpty);
        switch (reason) {
            case LANDED -> dismiss(player, DismissCause.LANDED);
            case FUEL_EMPTY -> dismiss(player, DismissCause.FUEL_EMPTY);
            case NONE -> applyAltitudeCeiling(player, session);
        }
    }

    /**
     * Clamps the flying entity (the mount in seated mode, the player themself in standing
     * mode) to {@code FlightGuard.clampAltitude}'s ceiling, teleporting it down if it climbed
     * above that. Always uses {@code RETAIN_PASSENGERS}: in seated mode that keeps the player
     * seated through the correction, and in standing mode it keeps the {@link CarpetVisual}
     * (attached as the player's own passenger — see the class Javadoc) from detaching.
     */
    @SuppressWarnings("deprecation") // TeleportFlag.EntityState: see SeatedFlightMode's note.
    private void applyAltitudeCeiling(Player player, CarpetSession session) {
        Entity flyer = session.mount() != null ? session.mount() : player;
        if (flyer.isDead() || !flyer.isValid()) {
            return;
        }
        Location current = flyer.getLocation();
        int clampedY = this.flightGuard.clampAltitude(current.getBlockY(), session.groundY());
        if (clampedY < current.getBlockY()) {
            Location clamped = current.clone();
            clamped.setY(clampedY);
            flyer.teleport(clamped, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
    }

    /**
     * Dismisses every active session, for {@code onDisable()}. Prefers the normal {@link
     * #dismiss} path (which needs a live {@link Player} to look up each {@code FlightMode}'s
     * own internal bookkeeping); falls back to removing the session's own entity references
     * directly for the — expected to be unreachable in practice — case where {@link
     * Bukkit#getPlayer(UUID)} already returns {@code null} during shutdown.
     */
    public void shutdownAll() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            CarpetSession session = sessions.get(id);
            if (session == null) {
                continue; // already torn down concurrently
            }
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                dismiss(player, DismissCause.SHUTDOWN);
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
     * Scans every loaded world for entities carrying the {@code magiccarpet} scoreboard tag
     * and removes them, returning how many were found. Meant to be called once from {@code
     * onEnable()}, after any previous run of the plugin may have crashed mid-flight and left
     * mounts or visuals behind with nothing left tracking them.
     */
    public int sweepOrphans() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(ORPHAN_TAG)) {
                    try {
                        entity.remove();
                        removed++;
                    } catch (RuntimeException e) {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to remove orphaned carpet entity " + entity.getUniqueId(), e);
                    }
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
         * The plugin ended the session unrequested: either {@link CarpetManager#shutdownAll()}
         * tearing down every session on plugin disable, or — reused for lack of a dedicated
         * "internal error" constant in this fixed set — the tick loop's own per-player guard
         * forcibly ending a session after catching an unexpected exception from it. Both cases
         * share the same shape: the plugin itself, not the player or an event, decided this
         * session could not continue.
         */
        SHUTDOWN
    }
}
