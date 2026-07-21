/*
 * MagicCarpet - tests for the post-combat redeploy cooldown bookkeeping.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link CombatGraceTracker} directly with plain UUIDs and tick numbers — no Bukkit type
 * is involved anywhere in this class, so it needs no live server.
 */
final class CombatGraceTrackerTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void aPlayerWithNoRecordedGraceIsNotActive() {
        CombatGraceTracker tracker = new CombatGraceTracker();

        assertFalse(tracker.isActive(PLAYER, 100));
    }

    @Test
    void graceIsActiveBeforeItsExpiryTick() {
        CombatGraceTracker tracker = new CombatGraceTracker();
        tracker.startGrace(PLAYER, 100, 40);

        assertTrue(tracker.isActive(PLAYER, 139));
    }

    @Test
    void graceHasExpiredExactlyAtStartPlusGraceTicks() {
        CombatGraceTracker tracker = new CombatGraceTracker();
        tracker.startGrace(PLAYER, 100, 40);

        assertFalse(tracker.isActive(PLAYER, 140));
    }

    @Test
    void zeroGraceTicksNeverActivatesACooldown() {
        CombatGraceTracker tracker = new CombatGraceTracker();
        tracker.startGrace(PLAYER, 100, 0);

        assertFalse(tracker.isActive(PLAYER, 100));
    }

    @Test
    void negativeGraceTicksNeverActivatesACooldown() {
        CombatGraceTracker tracker = new CombatGraceTracker();
        tracker.startGrace(PLAYER, 100, -5);

        assertFalse(tracker.isActive(PLAYER, 100));
    }

    @Test
    void clearRemovesAnActiveCooldown() {
        CombatGraceTracker tracker = new CombatGraceTracker();
        tracker.startGrace(PLAYER, 100, 40);

        tracker.clear(PLAYER);

        assertFalse(tracker.isActive(PLAYER, 105));
    }

    @Test
    void clearIsANoOpWhenNoCooldownIsRecorded() {
        CombatGraceTracker tracker = new CombatGraceTracker();

        tracker.clear(PLAYER);

        assertFalse(tracker.isActive(PLAYER, 0));
    }

    @Test
    void startingGraceAgainOverwritesAnEarlierExpiry() {
        CombatGraceTracker tracker = new CombatGraceTracker();
        tracker.startGrace(PLAYER, 100, 40); // would expire at 140
        tracker.startGrace(PLAYER, 200, 40); // now expires at 240 instead

        assertTrue(tracker.isActive(PLAYER, 150));
        assertFalse(tracker.isActive(PLAYER, 240));
    }

    @Test
    void isActiveAfterExpiryConsumesTheEntry() {
        CombatGraceTracker tracker = new CombatGraceTracker();
        tracker.startGrace(PLAYER, 100, 40);

        assertFalse(tracker.isActive(PLAYER, 140)); // expired, and cleans itself up
        // A fresh, later grace period for the same player still works after the old entry
        // was consumed above - proves the expiry cleanup does not corrupt future bookkeeping.
        tracker.startGrace(PLAYER, 500, 10);
        assertTrue(tracker.isActive(PLAYER, 505));
    }
}
