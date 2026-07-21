/*
 * MagicCarpet - tests for the look-direction/Input-to-offset vector math.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

final class CarpetMotionTest {

    private static final double DELTA = 1e-9;

    @Test
    void cruisesForwardAtConfiguredSpeedWithNoVerticalInput() {
        Vector offset = CarpetMotion.nextOffset(new Vector(0, 0, 1), false, false, 0.5);

        assertEquals(0.0, offset.getX(), DELTA);
        assertEquals(0.0, offset.getY(), DELTA);
        assertEquals(0.5, offset.getZ(), DELTA);
    }

    @Test
    void normalizesANonUnitLookDirectionBeforeScaling() {
        // (0, 0, 2) points the same way as (0, 0, 1) but is twice as long; the offset must
        // still be exactly `speed` long in that direction, not `speed * 2`.
        Vector offset = CarpetMotion.nextOffset(new Vector(0, 0, 2), false, false, 0.5);

        assertEquals(0.5, offset.length(), DELTA);
        assertEquals(0.5, offset.getZ(), DELTA);
    }

    @Test
    void sneakingAddsADownwardComponentOnTopOfCruise() {
        Vector offset = CarpetMotion.nextOffset(new Vector(0, 0, 1), true, false, 0.5);

        assertEquals(-0.5, offset.getY(), DELTA);
        assertEquals(0.5, offset.getZ(), DELTA);
    }

    @Test
    void jumpingAddsAnUpwardComponentOnTopOfCruise() {
        Vector offset = CarpetMotion.nextOffset(new Vector(0, 0, 1), false, true, 0.5);

        assertEquals(0.5, offset.getY(), DELTA);
        assertEquals(0.5, offset.getZ(), DELTA);
    }

    @Test
    void sneakingAndJumpingTogetherCancelOutVertically() {
        Vector offset = CarpetMotion.nextOffset(new Vector(0, 0, 1), true, true, 0.5);

        // The vertical components must cancel, but the horizontal cruise must survive: a
        // regression that zeroed the whole vector (not just Y) would previously have passed
        // this test with only the Y assertion.
        assertEquals(0.0, offset.getY(), DELTA);
        assertEquals(0.0, offset.getX(), DELTA);
        assertEquals(0.5, offset.getZ(), DELTA);
    }

    @Test
    void jumpingWithLevelLookPinsTheFortyOnePercentDiagonalSpeedBoostQuirk() {
        // Level look (0, 0, 1) plus jump held produces offset (0, speed, speed) -- magnitude
        // speed * sqrt(2), about 41% faster than level cruise alone (magnitude speed). This is
        // additive by design (see CarpetMotion class Javadoc) and NOT a bug: sneak/jump each
        // add a fixed vertical component on top of the cruise vector rather than being folded
        // into a single normalized direction. Retuning this is a gameplay/in-game-calibration
        // decision, not a code fix -- this test only pins the current magnitude so a future
        // change to it is deliberate, not accidental.
        Vector offset = CarpetMotion.nextOffset(new Vector(0, 0, 1), false, true, 0.5);

        assertEquals(0.5, offset.getY(), DELTA);
        assertEquals(0.5, offset.getZ(), DELTA);
        assertEquals(0.5 * Math.sqrt(2), offset.length(), DELTA);
    }

    @Test
    void diagonalLookDirectionKeepsOffsetMagnitudeAtSpeed() {
        Vector offset = CarpetMotion.nextOffset(new Vector(1, 1, 1), false, false, 0.9);

        assertEquals(0.9, offset.length(), DELTA);
    }

    @Test
    void zeroLengthLookDirectionFallsBackInsteadOfDividingByZero() {
        Vector offset = CarpetMotion.nextOffset(new Vector(0, 0, 0), false, false, 0.5);

        assertEquals(0.0, offset.getX(), DELTA);
        assertEquals(0.0, offset.getY(), DELTA);
        assertEquals(0.5, offset.getZ(), DELTA);
    }

    @Test
    void nullLookDirectionFallsBackInsteadOfThrowing() {
        Vector offset = CarpetMotion.nextOffset(null, false, false, 0.5);

        assertEquals(0.5, offset.getZ(), DELTA);
    }

    @Test
    void doesNotMutateTheSuppliedLookDirection() {
        Vector direction = new Vector(0, 0, 2);
        CarpetMotion.nextOffset(direction, true, false, 0.5);

        assertEquals(2.0, direction.getZ(), DELTA);
        assertEquals(0.0, direction.getX(), DELTA);
        assertEquals(0.0, direction.getY(), DELTA);
    }
}
