/*
 * MagicCarpet - pure vector math turning a look direction and Input into a movement offset.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.flight;

import org.bukkit.util.Vector;

/**
 * Computes the per-tick movement offset for {@link SeatedFlightMode}, kept free of any
 * Bukkit runtime dependency (a {@link Vector} is a plain value type, not a live-server
 * handle) so it can be unit tested directly. This is the one piece of flight-mode logic
 * genuinely separable from a running server; everything else in this package spawns,
 * teleports, or mutates live entities and cannot be exercised outside one.
 *
 * <p>The carpet cruises continuously in the direction the player is looking — forward input
 * is never required, this is not a walking simulator. Sneak and jump each add a fixed
 * vertical component of {@code speed} on top of that cruise vector; holding both cancels out.
 */
final class CarpetMotion {

    private CarpetMotion() {}

    /**
     * Returns the offset to add to the mount's current location for one tick.
     *
     * @param lookDirection the player's look direction; normalized defensively, and replaced
     *     with {@code (0, 0, 1)} if zero-length or {@code null} rather than dividing by zero
     * @param sneaking {@code true} to add a downward component of {@code speed}
     * @param jumping {@code true} to add an upward component of {@code speed}
     * @param speed blocks of travel per tick, taken from {@code config.flightSpeed()}
     */
    static Vector nextOffset(Vector lookDirection, boolean sneaking, boolean jumping, double speed) {
        Vector direction = normalizedOrFallback(lookDirection);
        Vector cruise = direction.multiply(speed);
        double vertical = (jumping ? speed : 0.0) - (sneaking ? speed : 0.0);
        cruise.setY(cruise.getY() + vertical);
        return cruise;
    }

    private static Vector normalizedOrFallback(Vector lookDirection) {
        if (lookDirection == null || lookDirection.lengthSquared() == 0.0) {
            return new Vector(0, 0, 1);
        }
        return lookDirection.clone().normalize();
    }
}
