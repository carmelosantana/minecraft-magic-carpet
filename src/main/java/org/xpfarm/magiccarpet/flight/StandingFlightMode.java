/*
 * MagicCarpet - client-driven flight where the player flies themselves and renders standing.
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
import org.xpfarm.magiccarpet.config.MagicCarpetConfig;

/**
 * Client-driven flight: the player is not a passenger of anything, so the vanilla client
 * never picks the seated riding animation for them ({@code HumanoidMobRenderer} keys that
 * pose off {@code state.isPassenger} alone). With {@code isPassenger()} false,
 * {@code Player.travel()} reaches the {@code this.getAbilities().flying} branch instead of
 * short-circuiting to the vehicle-riding branch, so ordinary creative-style client flight
 * abilities drive movement. The player renders standing, and the server does not move them
 * at all. See {@link SeatedFlightMode} for the opposite trade-off.
 *
 * <p><strong>Visual anchor.</strong> The player is never made a passenger of anything — doing
 * so would force the seated pose this mode exists to avoid. But {@code CarpetVisual} still
 * needs a live {@link Entity} to attach its two passengers to, and {@code CarpetVisual}'s
 * {@code TRANSLATE_Y} offset is calibrated for a {@code setSmall(true)} marker
 * {@link ArmorStand}'s passenger attachment point, not a full-size {@link Player}'s. So this
 * mode spawns its own marker {@link ArmorStand} — the same recipe {@link SeatedFlightMode}
 * uses for its mount, minus adding the player as a passenger — purely as a carrier for the
 * visual. {@link #tick} teleports that anchor to the player's current location every tick
 * with {@code TeleportFlag.EntityState.RETAIN_PASSENGERS} so the visual passengers ride along
 * without being ejected. {@link #deploy} returns the anchor, so {@code CarpetSession.mount()}
 * is non-null for a standing-flight session exactly as it is for a seated one; the two modes
 * are structurally identical from {@code CarpetVisual}'s point of view.
 *
 * <p>Altitude ceiling enforcement clamps the <em>player</em>, not the anchor, in this mode —
 * the anchor carries only the decoration, the player is what is actually flying — and belongs
 * to the caller (task 7's carpet manager), not this class.
 *
 * <p><strong>{@code flight.speed} does not apply to this mode.</strong> {@link #tick} does not
 * read {@code config.flightSpeed()} at all — movement here is entirely native client flight at
 * whatever speed the client's own flight ability moves at, not a server-computed offset the way
 * {@link SeatedFlightMode#tick} uses it. {@code flight.speed} only affects seated mode. Mapping
 * a blocks-per-tick value onto {@code Player#setFlySpeed} was deliberately not attempted here:
 * that API is an unrelated abstract 0.0-1.0 scale, not blocks per tick, and a guessed conversion
 * between the two would be worse than plainly documenting the gap — retuning standing-mode speed
 * is a live-server tuning decision, not a code fix.
 */
public final class StandingFlightMode implements FlightMode {

    /**
     * Scoreboard tag applied to the anchor. Deliberately the same literal value as {@code
     * SeatedFlightMode.MOUNT_TAG} / {@code CarpetVisual.VISUAL_TAG} so every carpet-related
     * entity is identifiable by the same tag for orphan sweeps.
     */
    private static final String ANCHOR_TAG = "magiccarpet";

    private final Map<UUID, TrackedState> tracked = new ConcurrentHashMap<>();

    /**
     * Records {@code player}'s current {@code getAllowFlight()}/{@code isFlying()} before
     * granting flight, so {@link #dismiss} can restore exactly those values rather than
     * assuming {@code false} — stripping flight from a creative-mode or already-flying player
     * would be a real regression, not a cosmetic one. Also spawns the marker {@link ArmorStand}
     * that carries the {@code CarpetVisual} passengers (see class Javadoc).
     *
     * <p>Rejects a duplicate deploy for a player who already has a tracked entry (i.e. no
     * intervening {@link #dismiss}), rather than silently overwriting it: since this mode has
     * already set {@code allowFlight}/{@code flying} to {@code true} from the first deploy,
     * overwriting the recorded state on a second call would capture the already-flying values
     * as "prior", and the eventual {@link #dismiss} would restore the wrong values. This
     * mirrors {@link SeatedFlightMode#deploy}'s duplicate-deploy guard for consistency across
     * both modes.
     *
     * @return the spawned marker {@link ArmorStand} anchor, or {@code null} if {@code at} has
     *     no world (a degenerate {@link Location} that cannot be spawned into) — in which case
     *     no player state is touched and nothing is tracked, matching {@link
     *     SeatedFlightMode#deploy}'s handling of the same degenerate case
     * @throws IllegalStateException if {@code player} already has an active tracked session
     */
    @Override
    public Entity deploy(Player player, Location at) {
        UUID id = player.getUniqueId();
        if (tracked.containsKey(id)) {
            throw new IllegalStateException(
                    "Player " + id + " already has an active standing-flight session; dismiss before redeploying.");
        }
        World world = at.getWorld();
        if (world == null) {
            return null;
        }
        ArmorStand anchor = world.spawn(at, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setSmall(true);
            s.setGravity(false);
            s.setBasePlate(false);
            s.setInvulnerable(true);
            s.setCollidable(false);
            s.setPersistent(false);
            s.addScoreboardTag(ANCHOR_TAG);
        });
        tracked.put(id, new TrackedState(player.getAllowFlight(), player.isFlying(), anchor));
        player.setAllowFlight(true);
        player.setFlying(true);
        return anchor;
    }

    /**
     * Teleports the visual anchor to {@code player}'s current location. This is the only
     * per-tick work this mode does — the client flies itself once {@code setAllowFlight(true)}
     * and {@code setFlying(true)} are set, so there is no player movement step to perform here.
     * Altitude ceiling enforcement is the caller's responsibility and, deliberately, is not
     * applied to the anchor: it is applied to the player, and this method re-syncs the anchor
     * to wherever the player ends up on the very next tick.
     *
     * <p>Never calls {@code setVelocity} — movement is exclusively {@code teleport(location,
     * TeleportFlag.EntityState.RETAIN_PASSENGERS)}, required to avoid ejecting the visual
     * passengers on every step.
     *
     * <p>{@code TeleportFlag.EntityState} is marked {@code @Deprecated(forRemoval = true)} as
     * of Paper 1.21.10, but it is the only mechanism the pinned {@code 26.1.2 build 74} API
     * offers for a passenger-preserving teleport; see {@link SeatedFlightMode#tick} for the
     * full note. Suppressed deliberately rather than switched to {@code setVelocity}, which is
     * prohibited.
     */
    @SuppressWarnings("deprecation")
    @Override
    public void tick(Player player, Input input, MagicCarpetConfig config) {
        TrackedState state = tracked.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        ArmorStand anchor = state.anchor();
        if (anchor.isDead() || !anchor.isValid()) {
            return;
        }
        anchor.teleport(player.getLocation(), TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    /**
     * Restores the flight state recorded at {@link #deploy} and removes the visual anchor.
     * Safe to call for a player who is offline, already dismissed, or was never deployed
     * through this instance — all such calls are no-ops.
     *
     * <p>The tracked entry is removed <strong>first</strong>, unconditionally, before either
     * cleanup step is even attempted — matching {@link SeatedFlightMode#dismiss}, which calls
     * {@code mounts.remove(id)} before its own guarded cleanup. This is a deliberate reversal
     * of an earlier version of this method, which removed the entry only after a successful
     * restore and left it in place on failure so "a later retry" could pick it up. That earlier
     * behaviour was itself the bug: no retry mechanism exists anywhere in this codebase to
     * consume a retained entry, but {@link #deploy} unconditionally rejects any UUID still
     * present in {@link #tracked}. So a single transient {@code RuntimeException} from {@code
     * setFlying}/{@code setAllowFlight}/{@code remove} — offline player, plugin conflict,
     * anything transient — permanently wedged that UUID: every future {@code deploy()} call for
     * it threw forever, with no self-healing path. Removing the entry first bounds the failure
     * instead: a failure here is now a best-effort miss on one player's flight flags or one
     * leaked anchor entity for this one dismiss, not a permanent denial of the feature. Do not
     * reintroduce the "keep the entry for a retry" behaviour without also building a retry
     * mechanism to use it.
     */
    @Override
    public void dismiss(Player player) {
        if (player == null) {
            return;
        }
        TrackedState state = tracked.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        try {
            player.setFlying(state.flying());
            player.setAllowFlight(state.allowFlight());
        } catch (RuntimeException ignored) {
            // Best-effort restore only: the entry above is already gone, so this failure cannot
            // wedge future deploy() calls for this player. See method Javadoc for why the entry
            // is removed before the restore is attempted rather than after it succeeds.
        }
        ArmorStand anchor = state.anchor();
        try {
            if (!anchor.isDead()) {
                anchor.remove();
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup only, for the same reason as the restore above. A leaked
            // anchor still carries the magiccarpet scoreboard tag, so a stuck one is caught by
            // CarpetManager.sweepOrphans on the next plugin enable even if this remove() fails.
        }
    }

    private record TrackedState(boolean allowFlight, boolean flying, ArmorStand anchor) {}
}
