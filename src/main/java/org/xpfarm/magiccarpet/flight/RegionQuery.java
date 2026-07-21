/*
 * MagicCarpet - abstraction over WorldGuard region checks, kept out of testable logic.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.flight;

/**
 * Answers whether flight is permitted at a given position, without pulling WorldGuard
 * (or any Bukkit type) into the tested decision logic. Task 9 supplies a real
 * implementation backed by WorldGuard; {@link #permissive()} is used when WorldGuard is
 * absent or {@code worldguard.respect-regions} is {@code false}.
 */
@FunctionalInterface
public interface RegionQuery {

    /** True if flight is allowed at the given position. */
    boolean flightAllowed(String worldName, int x, int y, int z);

    /** A query that allows flight everywhere. */
    static RegionQuery permissive() {
        return (worldName, x, y, z) -> true;
    }
}
