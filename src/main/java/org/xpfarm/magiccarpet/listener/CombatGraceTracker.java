/*
 * MagicCarpet - per-player cooldown tracking the post-combat carpet redeploy grace period.
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
 * Tracks, per player, the server tick at which a combat-triggered redeploy cooldown expires.
 *
 * <p>{@code config.yml}'s {@code combat.grace-ticks} is documented as "Ticks after being
 * knocked off before the carpet can be redeployed" — this class is that cooldown, nothing more.
 * It does not know why a player was knocked off (that is {@code CarpetListeners}'s job, on the
 * {@code EntityDamageEvent} path) and does not itself gate deploys (that is also {@code
 * CarpetListeners}'s job, on the {@code PlayerJumpEvent} path); it only remembers "until which
 * tick" for whichever UUIDs {@link #startGrace} was called for.
 *
 * <p>Pure bookkeeping over {@link UUID} and {@code int} tick numbers, with no Bukkit type
 * anywhere in its signature — unlike the rest of this package, it needs no live server and is
 * unit-testable directly.
 */
final class CombatGraceTracker {

    private final Map<UUID, Integer> expiryTicks = new ConcurrentHashMap<>();

    /**
     * Starts (or restarts) {@code playerId}'s cooldown, expiring at {@code currentTick +
     * graceTicks}. A non-positive {@code graceTicks} (the config's own valid minimum is {@code
     * 0}, meaning "no cooldown") clears any existing cooldown instead of recording one that
     * would immediately be expired.
     */
    void startGrace(UUID playerId, int currentTick, int graceTicks) {
        if (graceTicks <= 0) {
            expiryTicks.remove(playerId);
            return;
        }
        expiryTicks.put(playerId, currentTick + graceTicks);
    }

    /**
     * Whether {@code playerId} is still within a previously started cooldown at {@code
     * currentTick}. Once {@code currentTick} reaches the recorded expiry, the entry is removed
     * (this is the only place expired entries are cleaned up, so a player who is never checked
     * again — e.g. they quit and never rejoin — would otherwise leak one map entry forever;
     * {@code CarpetListeners} also calls {@link #clear} on quit to avoid relying on this alone).
     */
    boolean isActive(UUID playerId, int currentTick) {
        Integer expiry = expiryTicks.get(playerId);
        if (expiry == null) {
            return false;
        }
        if (currentTick >= expiry) {
            expiryTicks.remove(playerId);
            return false;
        }
        return true;
    }

    /** Drops any recorded cooldown for {@code playerId}. Safe to call with no cooldown set. */
    void clear(UUID playerId) {
        expiryTicks.remove(playerId);
    }
}
