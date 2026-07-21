/*
 * MagicCarpet - tests for the fuel tank state machine.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FuelTankTest {

    private static final double EPSILON = 1e-9;

    @Test
    void startsFull() {
        FuelTank tank = new FuelTank(100, 50);

        assertEquals(1.0, tank.fraction(), EPSILON);
        assertFalse(tank.isEmpty());
    }

    @Test
    void drainingBelowZeroClampsToEmpty() {
        FuelTank tank = new FuelTank(100, 50);

        tank.drain(1000);

        assertTrue(tank.isEmpty());
        assertEquals(0.0, tank.fraction(), EPSILON);
    }

    @Test
    void rechargingPastCapacityClampsAndFractionIsExactlyOne() {
        FuelTank tank = new FuelTank(100, 50);
        tank.drain(50);

        tank.recharge(10_000);

        assertEquals(1.0, tank.fraction());
        assertFalse(tank.isEmpty());
    }

    @Test
    void fullDrainThenExactRechargeTicksReturnsToFull() {
        int capacityTicks = 1200;
        int rechargeTicks = 2400;
        FuelTank tank = new FuelTank(capacityTicks, rechargeTicks);

        tank.drain(capacityTicks);
        assertTrue(tank.isEmpty());

        tank.recharge(rechargeTicks);

        assertEquals(1.0, tank.fraction(), EPSILON);
    }

    @Test
    void fractionStaysWithinZeroToOneAcrossInterleavedDrainAndRecharge() {
        FuelTank tank = new FuelTank(200, 300);

        int[] drains = {10, 250, 5, 400, 1};
        int[] recharges = {5, 1000, 0, 50, 400};

        for (int i = 0; i < drains.length; i++) {
            tank.drain(drains[i]);
            double afterDrain = tank.fraction();
            assertTrue(afterDrain >= 0.0 && afterDrain <= 1.0, "fraction out of range after drain: " + afterDrain);

            tank.recharge(recharges[i]);
            double afterRecharge = tank.fraction();
            assertTrue(afterRecharge >= 0.0 && afterRecharge <= 1.0, "fraction out of range after recharge: " + afterRecharge);
            assertTrue(afterRecharge >= afterDrain - EPSILON, "recharge must not decrease fraction");
        }
    }

    @Test
    void refillFromEmptyGivesFull() {
        FuelTank tank = new FuelTank(100, 50);
        tank.drain(1000);
        assertTrue(tank.isEmpty());

        tank.refill();

        assertEquals(1.0, tank.fraction(), EPSILON);
        assertFalse(tank.isEmpty());
    }

    @Test
    void negativeDrainIsTreatedAsZero() {
        FuelTank tank = new FuelTank(100, 50);

        tank.drain(-500);

        assertEquals(1.0, tank.fraction(), EPSILON);
    }

    @Test
    void negativeRechargeIsTreatedAsZero() {
        FuelTank tank = new FuelTank(100, 50);
        tank.drain(50);
        double before = tank.fraction();

        tank.recharge(-500);

        assertEquals(before, tank.fraction(), EPSILON);
    }

    @Test
    void zeroOrNegativeCapacityTicksThrows() {
        assertThrows(IllegalArgumentException.class, () -> new FuelTank(0, 50));
        assertThrows(IllegalArgumentException.class, () -> new FuelTank(-10, 50));
    }

    @Test
    void zeroOrNegativeRechargeTicksIsTreatedAsOneInsteadOfThrowing() {
        FuelTank zeroRecharge = new FuelTank(100, 0);
        zeroRecharge.drain(100);
        zeroRecharge.recharge(1);
        assertEquals(1.0, zeroRecharge.fraction(), EPSILON);

        FuelTank negativeRecharge = new FuelTank(100, -5);
        negativeRecharge.drain(100);
        negativeRecharge.recharge(1);
        assertEquals(1.0, negativeRecharge.fraction(), EPSILON);
    }

    @Test
    void isEmptyIsTrueAtExactlyZeroNotOnlyBelowZero() {
        FuelTank tank = new FuelTank(100, 50);

        tank.drain(100);

        assertTrue(tank.isEmpty());
    }

    @Test
    void isBelowFractionIsFalseForAFullTank() {
        FuelTank tank = new FuelTank(100, 50);

        assertFalse(tank.isBelowFraction(0.05));
    }

    @Test
    void isBelowFractionIsTrueForAnEmptyTank() {
        FuelTank tank = new FuelTank(100, 50);
        tank.drain(100);

        assertTrue(tank.isBelowFraction(0.05));
    }

    @Test
    void isBelowFractionIsTrueJustUnderTheThreshold() {
        FuelTank tank = new FuelTank(100, 50);
        tank.drain(96); // fraction = 0.04, just under a 0.05 threshold

        assertTrue(tank.isBelowFraction(0.05));
    }

    @Test
    void isBelowFractionIsFalseAtExactlyTheThreshold() {
        FuelTank tank = new FuelTank(100, 50);
        tank.drain(95); // fraction = 0.05 exactly: at the threshold, not below it

        assertFalse(tank.isBelowFraction(0.05));
    }

    @Test
    void isBelowFractionIsFalseJustAboveTheThreshold() {
        FuelTank tank = new FuelTank(100, 50);
        tank.drain(94); // fraction = 0.06, just above a 0.05 threshold

        assertFalse(tank.isBelowFraction(0.05));
    }
}
