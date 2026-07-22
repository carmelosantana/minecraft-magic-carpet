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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.xpfarm.magiccarpet.flight.FlightMode;
import org.xpfarm.magiccarpet.flight.FuelTank;
import org.xpfarm.magiccarpet.visual.CarpetVisual;

/**
 * One rider's active carpet flight: which {@link FlightMode} strategy is driving them, the
 * mount entity that strategy spawned (if any), the dual visual carried alongside them, the
 * fuel gauge draining while they are airborne, the ground level recorded at deploy time so
 * the altitude ceiling has a base to measure from, and the exact rug {@link ItemStack} taken
 * out of the player's off-hand when this session started.
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
    private final ItemStack heldRug;
    private final EquipmentSlot rugSlot;

    /**
     * Whether this rider has been clear of the ground at least once since deploying — the latch
     * that arms landing detection.
     *
     * <p>Deploy happens from a jump, so the rider starts at ground level and a ground probe reads
     * true on the first tick or two before the carpet has lifted them. Without this latch the
     * session would end the instant it began. Latching on "has been airborne" rather than counting
     * grace ticks makes that independent of how quickly the rider's position propagates after the
     * mount is spawned, which is timing this plugin does not control.
     *
     * <p>The one piece of mutable state in an otherwise immutable holder; only {@code
     * CarpetManager}'s tick loop writes it, on the main server thread.
     */
    private boolean hasBeenAirborne;

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
     * @param heldRug the exact {@link ItemStack} {@code CarpetManager.deploy} removed from the
     *     player's off-hand when this session started — not a freshly created replacement, so
     *     any durability/NBT/display-name changes the specific physical item carries survive
     *     the flight. May be an air/empty stack in the defensive case where the off-hand held
     *     nothing at deploy time; never {@code null}.
     * @param rugSlot the hand the rug was taken from, so stowing puts it back where the player
     *     had it. Either hand is accepted at deploy because Bedrock clients cannot place a carpet
     *     in the off-hand at all — see {@code CarpetItem.findHeldCarpet}.
     */
    public CarpetSession(
            UUID playerId,
            FlightMode mode,
            Entity mount,
            CarpetVisual visual,
            FuelTank fuelTank,
            int groundY,
            ItemStack heldRug,
            EquipmentSlot rugSlot) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.mount = mount;
        this.visual = Objects.requireNonNull(visual, "visual");
        this.fuelTank = Objects.requireNonNull(fuelTank, "fuelTank");
        this.groundY = groundY;
        this.heldRug = Objects.requireNonNull(heldRug, "heldRug");
        this.rugSlot = Objects.requireNonNull(rugSlot, "rugSlot");
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

    /** See {@link #hasBeenAirborne} — whether landing detection is armed for this session. */
    public boolean hasBeenAirborne() {
        return hasBeenAirborne;
    }

    /** Arms landing detection. Idempotent; never un-set for the life of the session. */
    public void markAirborne() {
        this.hasBeenAirborne = true;
    }

    /**
     * The exact rug {@link ItemStack} taken from the player's off-hand at deploy time. See the
     * constructor parameter doc for why this is the original stack, not a fresh replacement.
     */
    public ItemStack heldRug() {
        return heldRug;
    }

    /** The hand {@link #heldRug()} was taken from, and the one it is returned to on stow. */
    public EquipmentSlot rugSlot() {
        return rugSlot;
    }
}
