/*
 * MagicCarpet - one rider's in-progress carpet flight state.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.session;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.xpfarm.magiccarpet.flight.FlightMode;
import org.xpfarm.magiccarpet.flight.FuelTank;
import org.xpfarm.magiccarpet.visual.CarpetVisual;

/**
 * One rider's active carpet flight: which {@link FlightMode} strategy is driving them, the
 * mount entity that strategy spawned (if any), the dual visual carried alongside them, the
 * fuel gauge draining while they are airborne, and the ground level recorded at deploy time
 * so the altitude ceiling has a base to measure from.
 *
 * <p>Pure data holder — {@code CarpetManager} owns the map keyed by {@link #playerId()} and
 * drives every state transition; this class has no behaviour of its own beyond exposing its
 * fields. It carries no Bukkit event handling and does not itself spawn, move, or remove
 * anything.
 */
public final class CarpetSession {

    private final UUID playerId;
    private final FlightMode mode;
    private final Entity mount;
    private final CarpetVisual visual;
    private final FuelTank fuelTank;
    private final int groundY;

    /**
     * @param playerId the rider's UUID
     * @param mode the {@link FlightMode} strategy driving this session
     * @param mount the mount or visual-anchor entity {@code mode.deploy} returned; non-null for
     *     every session that actually reaches registration (see {@link #mount()})
     * @param visual the dual carpet visual attached to whichever entity is carrying it
     * @param fuelTank this player's fuel gauge; the same instance CarpetManager keeps in its
     *     per-player map beyond this session's lifetime
     * @param groundY the block Y the player was standing on at deploy time, used as the base
     *     for {@code FlightGuard.clampAltitude}
     */
    public CarpetSession(
            UUID playerId,
            FlightMode mode,
            Entity mount,
            CarpetVisual visual,
            FuelTank fuelTank,
            int groundY) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.mount = mount;
        this.visual = Objects.requireNonNull(visual, "visual");
        this.fuelTank = Objects.requireNonNull(fuelTank, "fuelTank");
        this.groundY = groundY;
    }

    public UUID playerId() {
        return playerId;
    }

    public FlightMode mode() {
        return mode;
    }

    /**
     * The mount or visual-anchor entity {@code mode.deploy} returned for this session. Non-null
     * for every registered session: both {@code SeatedFlightMode} and {@code StandingFlightMode}
     * always supply an entity to attach the {@link CarpetVisual}'s passengers to (see {@code
     * FlightMode#deploy}'s Javadoc — it returns {@code null} only in the shared degenerate case
     * where the deploy location has no {@link org.bukkit.World}, and that case never reaches
     * session registration, so it can never appear here).
     */
    public Entity mount() {
        return mount;
    }

    public CarpetVisual visual() {
        return visual;
    }

    public FuelTank fuelTank() {
        return fuelTank;
    }

    public int groundY() {
        return groundY;
    }
}
