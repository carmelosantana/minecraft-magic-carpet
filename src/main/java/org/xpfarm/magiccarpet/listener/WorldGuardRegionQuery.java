/*
 * MagicCarpet - reflective, absent-tolerant WorldGuard region check for carpet flight.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.listener;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.xpfarm.magiccarpet.flight.RegionQuery;

/**
 * Answers {@link RegionQuery#flightAllowed} by asking WorldGuard whether the {@code BUILD} flag
 * is in effect at that location, using it as a proxy for "this location is inside a protected
 * region where a special movement ability like carpet flight should not work."
 *
 * <p><strong>Why {@code BUILD} and not a dedicated flag.</strong> Stock WorldGuard ships no
 * "flight" region flag; registering a custom one would require this plugin to declare a hard
 * {@code depend} on WorldGuard and link its flag-registration API at {@code onEnable} time,
 * which is exactly what the "soft dependency, reflective only" constraint rules out. {@code
 * BUILD} (whether non-members may modify the region) is the closest stock flag to "is this
 * generally a restricted area a stranger shouldn't be exercising special abilities in," and is
 * the same proxy several other WorldGuard-integrating plugins use for gating non-build
 * abilities. This is a judgment call, not a documented WorldGuard convention — flagged here and
 * in the task report as the one thing about this class most worth a second look during gate 7a
 * against a real WorldGuard region.
 *
 * <p><strong>Reflective binding, resolved once.</strong> WorldGuard is never a compile-time
 * dependency of this module (there is no WorldGuard jar anywhere in this project's Maven
 * repository — confirmed before writing this class), so every WorldGuard/WorldEdit type here is
 * reached through {@link Class#forName} plus {@link Method}/{@link java.lang.reflect.Field}
 * lookups, never a hard import. {@link #attemptLink} performs every lookup exactly once and
 * caches the resulting {@link Method} handles (and the singleton {@code Flags.BUILD} instance);
 * {@link #flightAllowed} only ever invokes those cached handles — no {@code Class.forName} or
 * {@code getMethod} search happens on the deploy path. Two of the five cached methods
 * ({@code getPlatform}, {@code getRegionContainer}) are resolved via the *runtime* class of a
 * probe instance obtained during that one-time link (rather than a hardcoded WorldGuard-internal
 * class name) specifically so this class does not need to guess WorldGuard's non-public-API
 * package layout for those two interfaces; the entry point ({@code
 * com.sk89q.worldguard.WorldGuard}), the flag holder ({@code
 * com.sk89q.worldguard.protection.flags.Flags}), and the WorldEdit/WorldGuard types needed to
 * build the {@code testState} call ({@code com.sk89q.worldedit.util.Location}, {@code
 * com.sk89q.worldguard.LocalPlayer}, {@code com.sk89q.worldguard.protection.flags.StateFlag},
 * {@code com.sk89q.worldedit.bukkit.BukkitAdapter}) are WorldGuard 7's long-stable, publicly
 * documented extension surface and are named directly.
 *
 * <p><strong>Failure behaviour.</strong> Any failure during {@link #attemptLink} — WorldGuard
 * absent, a class/method/field missing, or any other reflective or runtime exception — is
 * caught and resolves to {@code null}; {@link #create} then hands back {@link
 * RegionQuery#permissive()} instead of an instance of this class. This mirrors {@code
 * EditionResolver}'s Floodgate-linking pattern (task 5) exactly, including splitting the
 * Bukkit-dependent plugin-presence check ({@link #create}) from the pure reflective link
 * attempt ({@link #attemptLink}) so the latter is unit-testable without a live server. Beyond
 * construction, {@link #flightAllowed} itself also fails open (returns {@code true}) on any
 * exception at call time — a deliberate extension past "construction failure only," since a
 * WorldGuard region query going wrong mid-server-run must never block every carpet deploy for
 * every player; see that method's Javadoc.
 */
public final class WorldGuardRegionQuery implements RegionQuery {

    private static final String WORLDGUARD_PLUGIN_NAME = "WorldGuard";
    private static final String WORLDGUARD_CLASS = "com.sk89q.worldguard.WorldGuard";
    private static final String FLAGS_CLASS = "com.sk89q.worldguard.protection.flags.Flags";
    private static final String STATE_FLAG_CLASS = "com.sk89q.worldguard.protection.flags.StateFlag";
    private static final String LOCAL_PLAYER_CLASS = "com.sk89q.worldguard.LocalPlayer";
    private static final String WORLDEDIT_LOCATION_CLASS = "com.sk89q.worldedit.util.Location";
    private static final String BUKKIT_ADAPTER_CLASS = "com.sk89q.worldedit.bukkit.BukkitAdapter";

    private final Method getInstanceMethod;
    private final Method getPlatformMethod;
    private final Method getRegionContainerMethod;
    private final Method createQueryMethod;
    private final Method testStateMethod;
    private final Method adaptMethod;
    private final Object buildFlag;
    private final Class<?> stateFlagClass;

    private WorldGuardRegionQuery(
            Method getInstanceMethod,
            Method getPlatformMethod,
            Method getRegionContainerMethod,
            Method createQueryMethod,
            Method testStateMethod,
            Method adaptMethod,
            Object buildFlag,
            Class<?> stateFlagClass) {
        this.getInstanceMethod = getInstanceMethod;
        this.getPlatformMethod = getPlatformMethod;
        this.getRegionContainerMethod = getRegionContainerMethod;
        this.createQueryMethod = createQueryMethod;
        this.testStateMethod = testStateMethod;
        this.adaptMethod = adaptMethod;
        this.buildFlag = buildFlag;
        this.stateFlagClass = stateFlagClass;
    }

    /**
     * Builds the real WorldGuard-backed query, or {@link RegionQuery#permissive()} if WorldGuard
     * is not enabled or its API could not be linked. Callers must not invoke this at all when
     * {@code worldguard.respect-regions} is {@code false} in {@code config.yml} — go straight to
     * {@link RegionQuery#permissive()} in that case, exactly as when this method itself falls
     * back to it, so a disabled setting and an absent/unlinkable WorldGuard behave identically.
     */
    public static RegionQuery create(Logger logger) {
        if (!Bukkit.getPluginManager().isPluginEnabled(WORLDGUARD_PLUGIN_NAME)) {
            return RegionQuery.permissive();
        }
        WorldGuardRegionQuery linked = attemptLink(logger);
        return linked != null ? linked : RegionQuery.permissive();
    }

    /**
     * Attempts the one-time reflective link, independent of the plugin-manager check so it can
     * be exercised directly in a test with no Bukkit server present. Returns {@code null} on any
     * failure. This module has no WorldGuard/WorldEdit dependency on its test classpath, so the
     * "WorldGuard absent" failure path is exercised for real, not simulated.
     */
    static WorldGuardRegionQuery attemptLink(Logger logger) {
        try {
            Class<?> worldGuardClass = Class.forName(WORLDGUARD_CLASS);
            Method getInstanceMethod = worldGuardClass.getMethod("getInstance");
            Object worldGuardInstance = getInstanceMethod.invoke(null);

            Method getPlatformMethod = worldGuardInstance.getClass().getMethod("getPlatform");
            Object platform = getPlatformMethod.invoke(worldGuardInstance);

            Method getRegionContainerMethod = platform.getClass().getMethod("getRegionContainer");
            Object container = getRegionContainerMethod.invoke(platform);

            Method createQueryMethod = container.getClass().getMethod("createQuery");
            Object probeQuery = createQueryMethod.invoke(container);

            Class<?> weLocationClass = Class.forName(WORLDEDIT_LOCATION_CLASS);
            Class<?> localPlayerClass = Class.forName(LOCAL_PLAYER_CLASS);
            Class<?> stateFlagClass = Class.forName(STATE_FLAG_CLASS);
            Class<?> stateFlagArrayClass = Array.newInstance(stateFlagClass, 0).getClass();

            Method testStateMethod = probeQuery.getClass()
                    .getMethod("testState", weLocationClass, localPlayerClass, stateFlagArrayClass);

            Class<?> flagsClass = Class.forName(FLAGS_CLASS);
            Object buildFlag = flagsClass.getField("BUILD").get(null);

            Class<?> bukkitAdapterClass = Class.forName(BUKKIT_ADAPTER_CLASS);
            Method adaptMethod = bukkitAdapterClass.getMethod("adapt", Location.class);

            return new WorldGuardRegionQuery(
                    getInstanceMethod,
                    getPlatformMethod,
                    getRegionContainerMethod,
                    createQueryMethod,
                    testStateMethod,
                    adaptMethod,
                    buildFlag,
                    stateFlagClass);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (logger != null) {
                logger.log(Level.WARNING,
                        "WorldGuard is enabled but its region-query API could not be linked "
                                + "reflectively; carpet flight will ignore WorldGuard regions",
                        e);
            }
            return null;
        }
    }

    /**
     * Re-resolves WorldGuard's current platform/region-container/query chain from the cached
     * {@link Method} handles (never re-searching the class for them) and asks whether {@code
     * BUILD} is allowed at the given block. Never throws: any exception here — WorldGuard
     * unloaded mid-run, a region container mid-reload, anything else transient — is caught and
     * treated as "allowed," the same fail-open behaviour {@link #create} uses when linking fails
     * up front. This is deliberately not "construction failure only": a live server can lose
     * WorldGuard (or hit some other transient failure in its API) after this object was
     * successfully built, and this method must not turn that into a broken carpet deploy path
     * for every player for the rest of the server's uptime.
     */
    @Override
    public boolean flightAllowed(String worldName, int x, int y, int z) {
        try {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return true; // Unknown world: nothing WorldGuard-protected to deny here.
            }
            Object worldGuardInstance = getInstanceMethod.invoke(null);
            Object platform = getPlatformMethod.invoke(worldGuardInstance);
            Object container = getRegionContainerMethod.invoke(platform);
            Object query = createQueryMethod.invoke(container);

            Object weLocation = adaptMethod.invoke(null, new Location(world, x, y, z));

            Object flags = Array.newInstance(stateFlagClass, 1);
            Array.set(flags, 0, buildFlag);

            Object result = testStateMethod.invoke(query, weLocation, null, flags);
            return !(result instanceof Boolean allowed) || allowed;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }
}
