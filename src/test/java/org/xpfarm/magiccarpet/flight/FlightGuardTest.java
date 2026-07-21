/*
 * MagicCarpet - tests for the deploy/world/altitude predicates in FlightGuard.
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.xpfarm.magiccarpet.config.FlightModeKind;
import org.xpfarm.magiccarpet.config.MagicCarpetConfig;
import org.xpfarm.magiccarpet.config.WorldListMode;

final class FlightGuardTest {

    private static MagicCarpetConfig config(WorldListMode mode, List<String> worldsList) {
        return config(mode, worldsList, 64);
    }

    private static MagicCarpetConfig config(
            WorldListMode mode, List<String> worldsList, int altitudeCeiling) {
        return new MagicCarpetConfig(
                FlightModeKind.SEATED,
                FlightModeKind.STANDING,
                0.5,
                altitudeCeiling,
                60,
                120,
                true,
                40,
                mode,
                worldsList,
                true,
                true,
                true);
    }

    // --- worldAllowed ---

    @Test
    void allowListPermitsAListedWorld() {
        FlightGuard guard =
                new FlightGuard(config(WorldListMode.ALLOW_LIST, List.of("world")), RegionQuery.permissive());

        assertTrue(guard.worldAllowed("world"));
    }

    @Test
    void allowListRejectsAnUnlistedWorld() {
        FlightGuard guard =
                new FlightGuard(config(WorldListMode.ALLOW_LIST, List.of("world")), RegionQuery.permissive());

        assertFalse(guard.worldAllowed("world_nether"));
    }

    @Test
    void denyListRejectsAListedWorld() {
        FlightGuard guard =
                new FlightGuard(config(WorldListMode.DENY_LIST, List.of("world_nether")), RegionQuery.permissive());

        assertFalse(guard.worldAllowed("world_nether"));
    }

    @Test
    void denyListPermitsAnUnlistedWorld() {
        FlightGuard guard =
                new FlightGuard(config(WorldListMode.DENY_LIST, List.of("world_nether")), RegionQuery.permissive());

        assertTrue(guard.worldAllowed("world"));
    }

    @Test
    void worldMatchingIsCaseInsensitive() {
        FlightGuard allowGuard =
                new FlightGuard(config(WorldListMode.ALLOW_LIST, List.of("World")), RegionQuery.permissive());
        FlightGuard denyGuard =
                new FlightGuard(config(WorldListMode.DENY_LIST, List.of("World")), RegionQuery.permissive());

        assertTrue(allowGuard.worldAllowed("wOrLd"));
        assertFalse(denyGuard.worldAllowed("wOrLd"));
    }

    @Test
    void emptyListUnderAllowListRejectsEverything() {
        FlightGuard guard =
                new FlightGuard(config(WorldListMode.ALLOW_LIST, List.of()), RegionQuery.permissive());

        assertFalse(guard.worldAllowed("world"));
    }

    @Test
    void emptyListUnderDenyListAllowsEverything() {
        FlightGuard guard =
                new FlightGuard(config(WorldListMode.DENY_LIST, List.of()), RegionQuery.permissive());

        assertTrue(guard.worldAllowed("world"));
    }

    // --- checkDeploy ordering ---

    @Test
    void missingPermissionShortCircuitsBeforeTheWorldCheck() {
        // The world is denied AND the player lacks permission; permission must win.
        FlightGuard guard = new FlightGuard(
                config(WorldListMode.DENY_LIST, List.of("world")), RegionQuery.permissive());

        Optional<DenyReason> result = guard.checkDeploy("world", 0, 64, 0, false);

        assertEquals(Optional.of(DenyReason.NO_PERMISSION), result);
    }

    @Test
    void worldDisabledIsReportedWhenPermissionIsPresent() {
        FlightGuard guard = new FlightGuard(
                config(WorldListMode.DENY_LIST, List.of("world")), RegionQuery.permissive());

        Optional<DenyReason> result = guard.checkDeploy("world", 0, 64, 0, true);

        assertEquals(Optional.of(DenyReason.WORLD_DISABLED), result);
    }

    @Test
    void regionDeniedIsReportedWhenPermissionAndWorldPass() {
        FlightGuard guard = new FlightGuard(
                config(WorldListMode.DENY_LIST, List.of()), (w, x, y, z) -> false);

        Optional<DenyReason> result = guard.checkDeploy("world", 0, 64, 0, true);

        assertEquals(Optional.of(DenyReason.REGION_DENIED), result);
    }

    @Test
    void permissiveRegionQueryAllowsEverything() {
        FlightGuard guard = new FlightGuard(
                config(WorldListMode.DENY_LIST, List.of()), RegionQuery.permissive());

        Optional<DenyReason> result = guard.checkDeploy("world", 12345, 64, -6789, true);

        assertEquals(Optional.empty(), result);
    }

    @Test
    void checkDeployAllowsWhenEveryCheckPasses() {
        FlightGuard guard = new FlightGuard(
                config(WorldListMode.ALLOW_LIST, List.of("world")), RegionQuery.permissive());

        Optional<DenyReason> result = guard.checkDeploy("world", 0, 64, 0, true);

        assertTrue(result.isEmpty());
    }

    // --- clampAltitude ---

    @Test
    void clampAltitudeAtTheCeilingReturnsCurrentY() {
        FlightGuard guard =
                new FlightGuard(config(WorldListMode.DENY_LIST, List.of(), 64), RegionQuery.permissive());

        assertEquals(164, guard.clampAltitude(164, 100));
    }

    @Test
    void clampAltitudeBelowTheCeilingReturnsCurrentY() {
        FlightGuard guard =
                new FlightGuard(config(WorldListMode.DENY_LIST, List.of(), 64), RegionQuery.permissive());

        assertEquals(120, guard.clampAltitude(120, 100));
    }

    @Test
    void clampAltitudeAboveTheCeilingIsClampedToTheCeiling() {
        FlightGuard guard =
                new FlightGuard(config(WorldListMode.DENY_LIST, List.of(), 64), RegionQuery.permissive());

        assertEquals(164, guard.clampAltitude(500, 100));
    }

    @Test
    void clampAltitudeBelowGroundReturnsGroundNotCurrentY() {
        FlightGuard guard =
                new FlightGuard(config(WorldListMode.DENY_LIST, List.of(), 64), RegionQuery.permissive());

        assertEquals(100, guard.clampAltitude(40, 100));
    }
}
