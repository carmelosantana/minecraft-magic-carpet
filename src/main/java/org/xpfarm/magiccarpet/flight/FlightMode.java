/*
 * MagicCarpet - strategy interface for the two ways a carpet can carry a flying player.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.flight;

import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.xpfarm.magiccarpet.config.MagicCarpetConfig;

/**
 * One of the two movement strategies behind carpet flight: {@link SeatedFlightMode}, where
 * the player rides a server-driven mount and renders in the seated animation, or
 * {@link StandingFlightMode}, where the client's own creative-style flight drives movement
 * and the player renders standing. The two cannot be merged into one implementation — see
 * each class's Javadoc for why.
 *
 * <p>{@link #tick} receives an already-polled {@link Input}; implementations must not call
 * {@link Player#getCurrentInput()} themselves. The caller (the carpet manager) polls once per
 * player per tick and passes the same {@code Input} to whichever mode is active, so the mode
 * stays free of that dependency and is not double-polled.
 */
public interface FlightMode {

    /**
     * Starts flight for {@code player} at {@code at}.
     *
     * @return the spawned mount entity for {@link SeatedFlightMode}, or {@code null} for
     *     {@link StandingFlightMode}, which has no mount. Callers must handle a {@code null}
     *     return.
     * @throws IllegalStateException if {@code player} already has an active session with this
     *     mode instance and was not {@link #dismiss}ed first, or (in {@link SeatedFlightMode}
     *     only) if the mount rejects the player as a passenger. Callers must not assume deploy
     *     always succeeds silently; a caught exception means no session was started and (for
     *     {@link SeatedFlightMode}) any entity spawned during the attempt has already been
     *     cleaned up.
     */
    Entity deploy(Player player, Location at);

    /**
     * Advances flight by one movement step using the already-polled {@code input}. Altitude
     * ceiling enforcement is the caller's responsibility, not this method's — it only moves.
     */
    void tick(Player player, Input input, MagicCarpetConfig config);

    /**
     * Stops flight and restores whatever normal player state this mode changed. Must be
     * idempotent and must not throw for a player who is offline, already dismounted, or was
     * never deployed through this mode instance.
     */
    void dismiss(Player player);
}
