/*
 * MagicCarpet - turns the client's held-jump input state into one-shot press events.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects the rising edge of the jump input — the tick a player <em>starts</em> holding jump —
 * from the level-triggered state {@code PlayerInputEvent} reports.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Deploy used to hang off Paper's {@code PlayerJumpEvent}, which is already a one-shot event.
 * That had to change: {@code PlayerJumpEvent} is derived from the player's <em>movement</em>
 * (it carries a from/to {@code Location} pair), and nothing establishes that Geyser's movement
 * translation produces the pattern Paper's jump detection expects — so it could not be relied on
 * for Bedrock players, who were unable to fly at all.
 *
 * <p>{@code PlayerInputEvent} is backed by {@code ServerboundPlayerInputPacket} instead, which
 * Geyser demonstrably populates for every Bedrock client: {@code InputCache.processInputs} sets
 * {@code .withJump(...)} from the Bedrock {@code JUMP_CURRENT_RAW} / {@code JUMP_DOWN} /
 * {@code AUTO_JUMPING_IN_WATER} input flags. Keying deploy off that packet makes the trigger work
 * for both editions by construction rather than by hoping a heuristic transfers.
 *
 * <p>The cost is that {@code Input#isJump()} is a <em>held state</em>, not a press: it stays true
 * for every input event that fires while the key is down, and {@code PlayerInputEvent} fires on
 * any input change (every WASD tap). Without edge detection a player holding jump would
 * re-trigger deploy continuously. This class converts that level signal into an edge.
 *
 * <p>Pure state machine with no Bukkit dependency, so it is unit tested directly — the same
 * treatment {@link CombatGraceTracker} gets, and for the same reason.
 */
final class JumpPressTracker {

    private final Map<UUID, Boolean> jumpHeld = new ConcurrentHashMap<>();

    /**
     * Records {@code jumpNow} as {@code playerId}'s current jump state and reports whether this
     * is the transition into holding jump.
     *
     * <p>Must be called for <strong>every</strong> input event, including ones that will be
     * rejected for other reasons (already flying, no carpet, no permission). Skipping the call
     * leaves the recorded state stale, so a jump held across the skipped window would read as a
     * fresh press later.
     *
     * <p>A player with no recorded state who is already holding jump counts as a press: that is
     * the first event after a join or a tracker clear, and treating it as "not a press" would
     * silently swallow a real deploy attempt.
     *
     * @return {@code true} only on a {@code false} (or unknown) to {@code true} transition
     */
    boolean isPress(UUID playerId, boolean jumpNow) {
        Boolean previous = jumpHeld.put(playerId, jumpNow);
        return jumpNow && (previous == null || !previous);
    }

    /** Forgets {@code playerId}'s jump state. Called on quit so the map cannot grow unbounded. */
    void clear(UUID playerId) {
        jumpHeld.remove(playerId);
    }
}
