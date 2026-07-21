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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Input;
import org.bukkit.Location;
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
 * <p>{@link #deploy} has no mount to spawn and returns {@code null}. {@link #tick} is a
 * complete no-op: the client flies itself, and altitude ceiling enforcement belongs to the
 * caller (task 7's carpet manager), not this class, so there is nothing left for a movement
 * step to do here.
 */
public final class StandingFlightMode implements FlightMode {

    private final Map<UUID, PriorFlightState> priorStates = new ConcurrentHashMap<>();

    /**
     * Records {@code player}'s current {@code getAllowFlight()}/{@code isFlying()} before
     * granting flight, so {@link #dismiss} can restore exactly those values rather than
     * assuming {@code false} — stripping flight from a creative-mode or already-flying player
     * would be a real regression, not a cosmetic one.
     *
     * <p>Rejects a duplicate deploy for a player who already has a tracked prior state (i.e.
     * no intervening {@link #dismiss}), rather than silently overwriting it: since this mode
     * has already set {@code allowFlight}/{@code flying} to {@code true} from the first
     * deploy, overwriting the recorded state on a second call would capture the already-flying
     * values as "prior", and the eventual {@link #dismiss} would restore the wrong values. This
     * mirrors {@link SeatedFlightMode#deploy}'s duplicate-deploy guard for consistency across
     * both modes.
     *
     * @return always {@code null} on success; this mode has no mount entity. Note this is
     *     indistinguishable from a {@code null} success return by return value alone — the
     *     failure signal here is the thrown exception, not the return value.
     * @throws IllegalStateException if {@code player} already has an active tracked session
     */
    @Override
    public Entity deploy(Player player, Location at) {
        UUID id = player.getUniqueId();
        if (priorStates.containsKey(id)) {
            throw new IllegalStateException(
                    "Player " + id + " already has an active standing-flight session; dismiss before redeploying.");
        }
        priorStates.put(id, new PriorFlightState(player.getAllowFlight(), player.isFlying()));
        player.setAllowFlight(true);
        player.setFlying(true);
        return null;
    }

    /**
     * No-op: the client drives its own flight once {@code setAllowFlight(true)} and
     * {@code setFlying(true)} are set, so there is no server-side movement step to perform.
     * Altitude ceiling enforcement is the caller's responsibility.
     */
    @Override
    public void tick(Player player, Input input, MagicCarpetConfig config) {
        // Intentionally empty. See class Javadoc.
    }

    /**
     * Restores the flight state recorded at {@link #deploy}. Safe to call for a player who is
     * offline, already dismissed, or was never deployed through this instance — all such
     * calls are no-ops.
     *
     * <p>The tracked entry is removed <strong>first</strong>, unconditionally, before the
     * restore is even attempted — matching {@link SeatedFlightMode#dismiss}, which calls
     * {@code mounts.remove(id)} before its own guarded cleanup. This is a deliberate reversal
     * of an earlier version of this method, which removed the entry only after a successful
     * restore and left it in place on failure so "a later retry" could pick it up. That earlier
     * behaviour was itself the bug: no retry mechanism exists anywhere in this codebase to
     * consume a retained entry, but {@link #deploy} unconditionally rejects any UUID still
     * present in {@link #priorStates}. So a single transient {@code RuntimeException} from
     * {@code setFlying}/{@code setAllowFlight} — offline player, plugin conflict, anything
     * transient — permanently wedged that UUID: every future {@code deploy()} call for it threw
     * forever, with no self-healing path. Removing the entry first bounds the failure instead:
     * a restore that throws is now a best-effort miss on one player's flight flags for this one
     * dismiss, not a permanent denial of the feature. Do not reintroduce the "keep the entry for
     * a retry" behaviour without also building a retry mechanism to use it.
     */
    @Override
    public void dismiss(Player player) {
        if (player == null) {
            return;
        }
        PriorFlightState prior = priorStates.remove(player.getUniqueId());
        if (prior == null) {
            return;
        }
        try {
            player.setFlying(prior.flying());
            player.setAllowFlight(prior.allowFlight());
        } catch (RuntimeException ignored) {
            // Best-effort restore only: the entry above is already gone, so this failure cannot
            // wedge future deploy() calls for this player. See method Javadoc for why the entry
            // is removed before the restore is attempted rather than after it succeeds.
        }
    }

    private record PriorFlightState(boolean allowFlight, boolean flying) {}
}
