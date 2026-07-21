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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Covers only the part of {@link WorldGuardRegionQuery} reachable without a running Bukkit
 * server or a WorldGuard installation.
 *
 * <p>{@link WorldGuardRegionQuery#create(java.util.logging.Logger)} calls {@code
 * Bukkit.getPluginManager()}, which needs a running server, so it is not exercised here. {@link
 * WorldGuardRegionQuery#attemptLink(java.util.logging.Logger)} and {@link
 * WorldGuardRegionQuery#registerFlag(java.util.logging.Logger)} do not touch Bukkit at all —
 * both are pure reflection against WorldGuard's API classes — and this module has neither
 * WorldGuard nor WorldEdit on its test (or main) classpath, so the "WorldGuard absent" failure
 * path is exercised for real for both, exactly the same way {@code EditionResolverTest}
 * exercises "Floodgate absent" for real.
 */
final class WorldGuardRegionQueryTest {

    @Test
    void attemptLinkReturnsNullWhenWorldGuardClassIsAbsent() {
        assertNull(WorldGuardRegionQuery.attemptLink(null));
    }

    /**
     * {@link WorldGuardRegionQuery#registerFlag} must never throw regardless of {@code logger}
     * being {@code null} — {@code MagicCarpetPlugin.onLoad()} calling it with WorldGuard absent
     * from the server (the common case for any server that has not installed WorldGuard) must
     * never fail plugin startup, per the global "startup never throws" constraint.
     */
    @Test
    void registerFlagDoesNotThrowWhenWorldGuardClassIsAbsent() {
        assertDoesNotThrow(() -> WorldGuardRegionQuery.registerFlag(null));
    }

    /**
     * Registration silently no-oping when WorldGuard is absent must not leave behind a linkable
     * query: a subsequent {@link WorldGuardRegionQuery#attemptLink} still fails closed to {@code
     * null} (permissive fallback), exactly as if {@code registerFlag} had never been called.
     * Guards against a regression where {@code registerFlag} accidentally cached or faked a flag
     * instance instead of genuinely resolving one through WorldGuard's registry.
     */
    @Test
    void attemptLinkStillFailsAfterRegisterFlagWhenWorldGuardIsAbsent() {
        WorldGuardRegionQuery.registerFlag(null);
        assertNull(WorldGuardRegionQuery.attemptLink(null));
    }

    @Test
    void flightFlagNameIsTheDocumentedCustomFlag() {
        assertEquals("magic-carpet-flight", WorldGuardRegionQuery.FLIGHT_FLAG_NAME);
    }
}
