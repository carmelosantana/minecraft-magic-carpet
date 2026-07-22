/*
 * MagicCarpet - tests for the ground-probe geometry, the server-independent core of the
 * rider clearance queries.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

/**
 * Covers {@code RiderClearance.groundProbe}, which is the part of landing detection that does
 * not need a live server. The {@code hasCollisionsIn} calls around it cannot be exercised here
 * (they need a world), so the probe's shape is what these tests pin down: get the shape wrong
 * and landing detection reports walls as floors, or misses the floor entirely.
 *
 * <p>{@link BoundingBox} is plain arithmetic with no registry access, so it is safe to construct
 * in a unit test — unlike {@code Material#isSolid()}, which the previous point-sample
 * implementation depended on and which throws without a live Paper registry.
 */
class RiderClearanceTest {

    /** A standard player hitbox: 0.6 wide, 1.8 tall, feet at y=64. */
    private static BoundingBox riderAt(double feetY) {
        return new BoundingBox(-0.3, feetY, -0.3, 0.3, feetY + 1.8, 0.3);
    }

    @Test
    void probeSitsDirectlyBeneathTheFeet() {
        BoundingBox probe = RiderClearance.groundProbe(riderAt(64.0), 0.08);

        assertEquals(63.92, probe.getMinY(), 1e-9, "probe must start below the feet");
        assertEquals(64.0, probe.getMaxY(), 1e-9, "probe must stop at the feet");
    }

    @Test
    void probeKeepsTheRidersHorizontalFootprint() {
        BoundingBox rider = riderAt(64.0);
        BoundingBox probe = RiderClearance.groundProbe(rider, 0.08);

        assertEquals(rider.getMinX(), probe.getMinX(), 1e-9);
        assertEquals(rider.getMaxX(), probe.getMaxX(), 1e-9);
        assertEquals(rider.getMinZ(), probe.getMinZ(), 1e-9);
        assertEquals(rider.getMaxZ(), probe.getMaxZ(), 1e-9);
    }

    @Test
    void probeNeverReachesUpIntoTheRider() {
        // The probe must not overlap the rider's own body, or a rider standing in a doorway
        // would read as grounded off their own torso rather than off the floor.
        BoundingBox rider = riderAt(64.0);
        BoundingBox probe = RiderClearance.groundProbe(rider, 0.08);

        assertTrue(probe.getMaxY() <= rider.getMinY(),
                "probe top must not rise above the rider's feet");
    }

    @Test
    void probeReachesStrictlyInsideTheBlockBelow() {
        // A rider standing on a block whose top face is y=64 has feet at exactly 64.0. A probe
        // that merely touched that plane would not reliably intersect; it has to reach into the
        // block. This is the difference between landing working and landing never firing.
        BoundingBox probe = RiderClearance.groundProbe(riderAt(64.0), 0.08);

        assertTrue(probe.getMinY() < 64.0, "probe must penetrate the block below the feet");
    }

    @Test
    void probeFollowsTheRiderUpward() {
        BoundingBox probe = RiderClearance.groundProbe(riderAt(70.5), 0.08);

        assertEquals(70.42, probe.getMinY(), 1e-9);
        assertEquals(70.5, probe.getMaxY(), 1e-9);
    }
}
