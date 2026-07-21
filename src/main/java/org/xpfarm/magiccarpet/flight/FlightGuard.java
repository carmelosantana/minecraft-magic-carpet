/*
 * MagicCarpet - predicates deciding whether flight may start or how high it may climb.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.flight;

import java.util.Optional;
import org.xpfarm.magiccarpet.config.MagicCarpetConfig;
import org.xpfarm.magiccarpet.config.WorldListMode;

/**
 * Decides whether a player may deploy a carpet at a position, and how high they may
 * climb once airborne. Deliberately free of Bukkit types — world identity is a
 * {@code String} name and coordinates are {@code int}s — so it is fully unit-testable;
 * the Bukkit-side caller unwraps {@code Location}/{@code World} into these primitives.
 */
public final class FlightGuard {

    private final MagicCarpetConfig config;
    private final RegionQuery regionQuery;

    public FlightGuard(MagicCarpetConfig config, RegionQuery regionQuery) {
        this.config = config;
        this.regionQuery = regionQuery;
    }

    /**
     * Checks whether a player may deploy their carpet at the given position.
     *
     * <p>Checks run in order: permission, then world list, then region. A player lacking
     * permission in a disabled world reports {@link DenyReason#NO_PERMISSION}, not
     * {@link DenyReason#WORLD_DISABLED}.
     *
     * @return empty when deployment is allowed, otherwise the first applicable reason
     */
    public Optional<DenyReason> checkDeploy(
            String worldName, int x, int y, int z, boolean hasUsePermission) {
        if (!hasUsePermission) {
            return Optional.of(DenyReason.NO_PERMISSION);
        }
        if (!worldAllowed(worldName)) {
            return Optional.of(DenyReason.WORLD_DISABLED);
        }
        if (!regionQuery.flightAllowed(worldName, x, y, z)) {
            return Optional.of(DenyReason.REGION_DENIED);
        }
        return Optional.empty();
    }

    /**
     * Honours {@code worlds.mode}: in {@link WorldListMode#ALLOW_LIST} only listed worlds
     * pass; in {@link WorldListMode#DENY_LIST} listed worlds fail. World name comparison
     * is case-insensitive.
     */
    public boolean worldAllowed(String worldName) {
        boolean listed = config.worldsList().stream()
                .anyMatch(listedName -> listedName.equalsIgnoreCase(worldName));
        return config.worldsMode() == WorldListMode.ALLOW_LIST ? listed : !listed;
    }

    /**
     * Returns the highest permitted Y given {@code flight.altitude-ceiling} blocks above
     * {@code groundY}, never below {@code groundY} itself.
     */
    public int clampAltitude(int currentY, int groundY) {
        int ceiling = groundY + config.flightAltitudeCeiling();
        return Math.max(groundY, Math.min(currentY, ceiling));
    }
}
