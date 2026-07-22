/*
 * MagicCarpet - tests for jump press-edge detection.
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
 * Covers the edge detection that lets a level-triggered input state drive a one-shot deploy.
 * Getting this wrong is not subtle: treating the held state as a press would re-deploy on every
 * input event for as long as the player holds jump.
 */
class JumpPressTrackerTest {

    private final JumpPressTracker tracker = new JumpPressTracker();
    private final UUID player = UUID.randomUUID();

    @Test
    void firstJumpIsAPress() {
        assertTrue(tracker.isPress(player, true));
    }

    @Test
    void holdingJumpDoesNotRepeatThePress() {
        assertTrue(tracker.isPress(player, true));
        assertFalse(tracker.isPress(player, true), "a held jump must not re-trigger deploy");
        assertFalse(tracker.isPress(player, true));
    }

    @Test
    void releasingAndPressingAgainIsANewPress() {
        assertTrue(tracker.isPress(player, true));
        assertFalse(tracker.isPress(player, false));
        assertTrue(tracker.isPress(player, true), "a fresh press after release must deploy again");
    }

    @Test
    void notJumpingIsNeverAPress() {
        assertFalse(tracker.isPress(player, false));
        assertFalse(tracker.isPress(player, false));
    }

    @Test
    void playersAreTrackedIndependently() {
        UUID other = UUID.randomUUID();
        assertTrue(tracker.isPress(player, true));
        assertTrue(tracker.isPress(other, true), "one player's jump must not mask another's");
        assertFalse(tracker.isPress(player, true));
    }

    @Test
    void clearForgetsTheHeldState() {
        assertTrue(tracker.isPress(player, true));
        assertFalse(tracker.isPress(player, true));

        tracker.clear(player);

        // After a clear (quit/rejoin) the first event showing jump held counts as a press again,
        // rather than being swallowed because of state from a previous session.
        assertTrue(tracker.isPress(player, true));
    }

    @Test
    void clearingAnUntrackedPlayerIsHarmless() {
        tracker.clear(UUID.randomUUID());
        assertTrue(tracker.isPress(player, true));
    }
}
