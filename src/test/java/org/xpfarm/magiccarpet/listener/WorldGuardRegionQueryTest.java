/*
 * MagicCarpet - tests for the server-independent half of WorldGuard's reflective link.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.listener;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Covers only the part of {@link WorldGuardRegionQuery} reachable without a running Bukkit
 * server or a WorldGuard installation.
 *
 * <p>{@link WorldGuardRegionQuery#create(java.util.logging.Logger)} calls {@code
 * Bukkit.getPluginManager()}, which needs a running server, so it is not exercised here. {@link
 * WorldGuardRegionQuery#attemptLink(java.util.logging.Logger)} does not touch Bukkit at all — it
 * is pure reflection against WorldGuard's API classes — and this module has neither WorldGuard
 * nor WorldEdit on its test (or main) classpath, so the "WorldGuard absent" failure path is
 * exercised for real, exactly the same way {@code EditionResolverTest} exercises "Floodgate
 * absent" for real.
 */
final class WorldGuardRegionQueryTest {

    @Test
    void attemptLinkReturnsNullWhenWorldGuardClassIsAbsent() {
        assertNull(WorldGuardRegionQuery.attemptLink(null));
    }
}
