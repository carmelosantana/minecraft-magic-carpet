/*
 * MagicCarpet - server-driven flight where the player rides a mount and renders seated.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.flight;

import io.papermc.paper.entity.TeleportFlag;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.xpfarm.magiccarpet.config.MagicCarpetConfig;

/**
 * Server-driven flight: the player rides an invisible {@link ArmorStand} mount as a
 * passenger. That is what makes the vanilla client render them in the seated riding
 * animation — {@code HumanoidMobRenderer} keys that pose off {@code state.isPassenger}
 * alone, with no per-vehicle hook, so a player renders seated if and only if they are a
 * passenger of something.
 *
 * <p>The cost of that pose: {@code Player.travel()} short-circuits to
 * {@code super.travel(input)} whenever {@code isPassenger()} is true, before the client's own
 * flight abilities are ever consulted. The client cannot fly itself while seated, so the
 * server must drive every movement step here instead. See {@link StandingFlightMode} for the
 * opposite trade-off: the client flies itself, but the player renders standing.
 *
 * <p>{@code tick} does not receive the mount entity — the interface passes only
 * {@code (player, input, config)} — so each deployed mount is tracked internally, keyed by
 * player UUID, and looked up again on every {@code tick}/{@code dismiss} call.
 */
public final class SeatedFlightMode implements FlightMode {

    /**
     * Scoreboard tag applied to the mount. Deliberately the same literal value as
     * {@code CarpetVisual.VISUAL_TAG} (task 5) so mount and visuals are identifiable by the
     * same tag; this class does not import that class to keep the flight package independent
     * of the visual package, which task 7's manager wires together.
     */
    private static final String MOUNT_TAG = "magiccarpet";

    /**
     * How far above the deploy location the mount is spawned, in blocks.
     *
     * <p>The rider jumps to unfurl the carpet, so the deploy {@link Location} is at their feet,
     * on the ground. Spawning the mount exactly there leaves the rider still touching the floor,
     * and since {@link CarpetMotion} cruises in the look direction with no automatic climb, a
     * rider looking level would simply skim the ground for the whole flight — which, now that
     * landing actually ends the session (see {@code CarpetManager.tickSession}), would stow the
     * carpet again on the very next tick. Lifting the mount clear of the ground makes takeoff
     * unambiguous and matches the fiction of a carpet picking you up.
     *
     * <p>Applied only when the rider actually fits there — see {@link #liftedSpawn}.
     */
    private static final double DEPLOY_LIFT = 1.2;

    private final Map<UUID, ArmorStand> mounts = new ConcurrentHashMap<>();

    /**
     * Spawns the mount at {@code at} and adds {@code player} as its passenger.
     *
     * <p>Two failure modes are guarded explicitly rather than left to silently corrupt {@link
     * #mounts}: a rejected passenger attach (see {@link #deploy} throws doc) and a duplicate
     * deploy for a player who already has a live mount tracked. Both are converted into a
     * thrown {@link IllegalStateException} rather than a silent no-op, matching the precedent
     * set by {@code CarpetVisual.attachPassenger} (task 5) for the identical
     * {@code addPassenger} rejection case.
     *
     * @return the spawned {@link ArmorStand}, or {@code null} if {@code at} has no world
     *     (a degenerate {@link Location} that cannot be spawned into)
     * @throws IllegalStateException if {@code player} already has a live tracked mount (caller
     *     must {@link #dismiss} first), or if the newly spawned mount rejects {@code player} as
     *     a passenger — in the latter case the spawned {@link ArmorStand} is removed before the
     *     exception propagates, so no orphaned entity or stale tracking entry is left behind
     */
    @Override
    public Entity deploy(Player player, Location at) {
        World world = at.getWorld();
        if (world == null) {
            return null;
        }
        UUID id = player.getUniqueId();
        ArmorStand existing = mounts.get(id);
        if (existing != null && !existing.isDead() && existing.isValid()) {
            throw new IllegalStateException(
                    "Player " + id + " already has an active seated-flight mount; dismiss before redeploying.");
        }
        ArmorStand stand = world.spawn(liftedSpawn(player, at), ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setSmall(true);
            s.setGravity(false);
            s.setBasePlate(false);
            s.setInvulnerable(true);
            s.setCollidable(false);
            s.setPersistent(false);
            s.addScoreboardTag(MOUNT_TAG);
        });
        if (!stand.addPassenger(player)) {
            removeIfAlive(stand);
            throw new IllegalStateException(
                    "Mount " + stand.getUniqueId() + " rejected passenger " + id);
        }
        mounts.put(id, stand);
        return stand;
    }

    /**
     * {@code at} raised by {@link #DEPLOY_LIFT}, or {@code at} unchanged when the rider would not
     * fit at that height — deploying under a low ceiling must not shove them into it. Falling back
     * to the unlifted location is safe: {@code CarpetManager} only arms landing detection once the
     * rider has been clear of the ground at least once, so a rider who could not be lifted keeps
     * flying (and can climb out by looking up or holding jump) rather than instantly re-stowing.
     */
    private static Location liftedSpawn(Player player, Location at) {
        Vector lift = new Vector(0, DEPLOY_LIFT, 0);
        if (RiderClearance.collidesAfterMoving(player, lift)) {
            return at;
        }
        return at.clone().add(lift);
    }

    /**
     * Removes {@code stand} if it is still alive, swallowing any exception {@code remove()}
     * itself throws. Used only for cleaning up a just-spawned mount after a rejected passenger
     * attach; a secondary exception here must never replace the original failure being
     * propagated.
     */
    private static void removeIfAlive(ArmorStand stand) {
        try {
            if (!stand.isDead()) {
                stand.remove();
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup only.
        }
    }

    /**
     * Moves the mount one step: continuous forward cruise in the direction the player is
     * looking, plus a vertical component from sneak/jump, computed by {@link CarpetMotion}.
     * Never calls {@code setVelocity} — movement is exclusively
     * {@code teleport(location, TeleportFlag.EntityState.RETAIN_PASSENGERS)}, which is
     * required to avoid ejecting the player passenger on every step.
     *
     * <p>{@code TeleportFlag.EntityState} is marked {@code @Deprecated(forRemoval = true)}
     * as of Paper 1.21.10, but it is the only mechanism the pinned {@code 26.1.2 build 74}
     * API offers for a passenger-preserving teleport; there is no replacement class in this
     * jar (verified by inspecting every {@code *Teleport*} class it ships). Suppressed
     * deliberately rather than switched to {@code setVelocity}, which is prohibited.
     *
     * <p><strong>Collision check before moving.</strong> A raw teleport does not collide — unlike
     * vanilla movement, nothing stops it from placing the mount (and the seated player riding it)
     * inside solid terrain, e.g. flying straight at a hillside. Before teleporting, this asks
     * whether the <em>rider's</em> hitbox would intersect terrain at the destination and, if so,
     * skips the teleport for this tick entirely: the carpet stops advancing rather than phasing
     * into the block, and tries again next tick (so turning away or climbing over the obstruction
     * resumes movement immediately, with no separate "stuck" state to track or clear). See {@link
     * #wouldTrapRider} for why the rider's box rather than the mount's position, and {@link
     * RiderClearance} for the world query itself — one {@code hasCollisionsIn} call per tick per
     * rider, the same cheap budget the altitude ceiling's terrain sample uses.
     */
    @SuppressWarnings("deprecation")
    @Override
    public void tick(Player player, Input input, MagicCarpetConfig config) {
        ArmorStand stand = mounts.get(player.getUniqueId());
        if (stand == null || stand.isDead() || !stand.isValid()) {
            return;
        }
        Vector direction = player.getLocation().getDirection();
        Vector offset =
                CarpetMotion.nextOffset(direction, input.isSneak(), input.isJump(), config.flightSpeed());
        Location next = stand.getLocation().add(offset);
        next.setDirection(direction);
        if (wouldTrapRider(player, offset)) {
            return;
        }
        stand.teleport(next, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    /**
     * Whether moving by {@code offset} would push {@code player} into terrain.
     *
     * <p>Tests the rider's own hitbox, not the mount's position. The mount is a zero-hitbox
     * marker ArmorStand that never collides with anything; the rider hanging off it is 1.8 blocks
     * tall and is what actually ends up inside a wall. The original single-block sample at the
     * mount's own position is what let a rider fly head-first into a dirt wall and suffocate
     * (field report, v0.1.0) — the check was testing the wrong <em>volume</em>, which is why it
     * reproduced at the default flight speed rather than only at the high speeds the pre-release
     * limitation note anticipated.
     *
     * <p>A rider who is <em>already</em> embedded is deliberately allowed to keep moving: refusing
     * to move them would trap them inside the terrain permanently, which is worse than the
     * collision this method exists to prevent. See {@link RiderClearance#isEmbedded}.
     */
    private static boolean wouldTrapRider(Player player, Vector offset) {
        return RiderClearance.collidesAfterMoving(player, offset)
                && !RiderClearance.isEmbedded(player);
    }

    /**
     * Removes the tracked mount, if any. Safe to call for a player who is offline, already
     * dismounted, or was never deployed through this instance — all such calls are no-ops.
     */
    @Override
    public void dismiss(Player player) {
        if (player == null) {
            return;
        }
        ArmorStand stand = mounts.remove(player.getUniqueId());
        if (stand == null) {
            return;
        }
        try {
            stand.remove();
        } catch (RuntimeException ignored) {
            // Best-effort cleanup: the entity may already be gone (e.g. world unload).
        }
    }
}
