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
     * @return always {@code null}; this mode has no mount entity
     */
    @Override
    public Entity deploy(Player player, Location at) {
        priorStates.put(
                player.getUniqueId(),
                new PriorFlightState(player.getAllowFlight(), player.isFlying()));
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
        player.setFlying(prior.flying());
        player.setAllowFlight(prior.allowFlight());
    }

    private record PriorFlightState(boolean allowFlight, boolean flying) {}
}
