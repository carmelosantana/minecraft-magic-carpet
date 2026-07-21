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
 * Answers {@link RegionQuery#flightAllowed} by asking WorldGuard whether the dedicated {@code
 * magic-carpet-flight} region flag is in effect at that location.
 *
 * <p><strong>A dedicated flag, not {@code BUILD}.</strong> An earlier revision of this class used
 * {@code Flags.BUILD} as a proxy for "flight should not work here." That was wrong: BUILD and
 * flight are different questions — a spawn area, shop, or museum routinely denies BUILD to
 * non-members while having no objection to someone flying over it, so every no-build region
 * silently became a no-fly region with no way for an admin to turn that off short of disabling
 * WorldGuard integration entirely. This class instead registers and queries its own {@link
 * com.sk89q.worldguard.protection.flags.StateFlag StateFlag} named {@code magic-carpet-flight},
 * defaulting to {@code ALLOW} — the standard WorldGuard idiom for exactly this situation. Every
 * existing region keeps working unchanged; an admin who wants a no-fly region sets the flag
 * explicitly ({@code /rg flag <region> magic-carpet-flight deny}).
 *
 * <p><strong>Registration must happen in {@code onLoad()}, not {@code onEnable()}.</strong>
 * WorldGuard locks its {@code FlagRegistry} once it has loaded its own region data, which
 * happens before any plugin's {@code onEnable()} runs but after every plugin's {@code onLoad()}
 * has run — registering later throws or is silently ignored depending on WorldGuard's version.
 * {@link #registerFlag} is the static entry point for this; {@code MagicCarpetPlugin.onLoad()}
 * (task 10) MUST call it exactly once, unconditionally, regardless of {@code
 * worldguard.respect-regions} (config may not even be loaded yet at {@code onLoad()} time, and
 * registering an unused flag is harmless). See that method's Javadoc for the exact call and its
 * failure behaviour.
 *
 * <p><strong>Reflective binding, resolved once.</strong> WorldGuard is never a compile-time
 * dependency of this module (there is no WorldGuard jar anywhere in this project's Maven
 * repository — confirmed before writing this class), so every WorldGuard/WorldEdit type here is
 * reached through {@link Class#forName} plus {@link Method}/{@link
 * java.lang.reflect.Constructor} lookups, never a hard import. {@link #attemptLink} performs
 * every lookup exactly once and caches the resulting {@link Method} handles (and the resolved
 * {@code magic-carpet-flight} flag instance, looked up from WorldGuard's {@code FlagRegistry} by
 * name — see below); {@link #flightAllowed} only ever invokes those cached handles — no {@code
 * Class.forName} or {@code getMethod} search happens on the deploy path. Two of the five cached
 * methods ({@code getPlatform}, {@code getRegionContainer}) are resolved via the *runtime* class
 * of a probe instance obtained during that one-time link (rather than a hardcoded
 * WorldGuard-internal class name) specifically so this class does not need to guess WorldGuard's
 * non-public-API package layout for those two interfaces; the entry point ({@code
 * com.sk89q.worldguard.WorldGuard}), the flag base type ({@code
 * com.sk89q.worldguard.protection.flags.Flag}), the flag type registered ({@code
 * com.sk89q.worldguard.protection.flags.StateFlag}), and the WorldEdit/WorldGuard types needed to
 * build the {@code testState} call ({@code com.sk89q.worldedit.util.Location}, {@code
 * com.sk89q.worldguard.LocalPlayer}, {@code com.sk89q.worldedit.bukkit.BukkitAdapter}) are
 * WorldGuard 7's long-stable, publicly documented extension surface and are named directly.
 *
 * <p><strong>Failure behaviour.</strong> Any failure during {@link #attemptLink} — WorldGuard
 * absent, the {@code magic-carpet-flight} flag never having been registered (task 10's {@code
 * onLoad()} wiring missing or run too late), a class/method/field missing, or any other
 * reflective or runtime exception — is caught and resolves to {@code null}; {@link #create} then
 * hands back {@link RegionQuery#permissive()} instead of an instance of this class. This mirrors
 * {@code EditionResolver}'s Floodgate-linking pattern (task 5) exactly, including splitting the
 * Bukkit-dependent plugin-presence check ({@link #create}) from the pure reflective link attempt
 * ({@link #attemptLink}) so the latter is unit-testable without a live server. Beyond
 * construction, {@link #flightAllowed} itself also fails open (returns {@code true}) on any
 * exception at call time — a deliberate extension past "construction failure only," since a
 * WorldGuard region query going wrong mid-server-run must never block every carpet deploy for
 * every player; see that method's Javadoc. {@link #registerFlag} independently never throws
 * either, for the same "startup never fails" reason (see the global constraints).
 */
public final class WorldGuardRegionQuery implements RegionQuery {

    private static final String WORLDGUARD_PLUGIN_NAME = "WorldGuard";
    private static final String WORLDGUARD_CLASS = "com.sk89q.worldguard.WorldGuard";
    private static final String FLAG_CLASS = "com.sk89q.worldguard.protection.flags.Flag";
    private static final String STATE_FLAG_CLASS = "com.sk89q.worldguard.protection.flags.StateFlag";
    private static final String LOCAL_PLAYER_CLASS = "com.sk89q.worldguard.LocalPlayer";
    private static final String WORLDEDIT_LOCATION_CLASS = "com.sk89q.worldedit.util.Location";
    private static final String BUKKIT_ADAPTER_CLASS = "com.sk89q.worldedit.bukkit.BukkitAdapter";

    /**
     * Name of the custom {@code StateFlag} this class registers ({@link #registerFlag}) and
     * queries ({@link #attemptLink}). Default is {@code ALLOW} — see {@link #registerFlag}.
     */
    static final String FLIGHT_FLAG_NAME = "magic-carpet-flight";

    private final Method getInstanceMethod;
    private final Method getPlatformMethod;
    private final Method getRegionContainerMethod;
    private final Method createQueryMethod;
    private final Method testStateMethod;
    private final Method adaptMethod;
    private final Object flightFlag;
    private final Class<?> stateFlagClass;

    private WorldGuardRegionQuery(
            Method getInstanceMethod,
            Method getPlatformMethod,
            Method getRegionContainerMethod,
            Method createQueryMethod,
            Method testStateMethod,
            Method adaptMethod,
            Object flightFlag,
            Class<?> stateFlagClass) {
        this.getInstanceMethod = getInstanceMethod;
        this.getPlatformMethod = getPlatformMethod;
        this.getRegionContainerMethod = getRegionContainerMethod;
        this.createQueryMethod = createQueryMethod;
        this.testStateMethod = testStateMethod;
        this.adaptMethod = adaptMethod;
        this.flightFlag = flightFlag;
        this.stateFlagClass = stateFlagClass;
    }

    /**
     * Registers the {@link #FLIGHT_FLAG_NAME} {@code StateFlag} with WorldGuard, defaulting to
     * {@code ALLOW}, reflectively and with no compile-time WorldGuard dependency.
     *
     * <p><strong>Hard requirement for task 10: call this from {@code MagicCarpetPlugin.onLoad()},
     * never from {@code onEnable()}.</strong> WorldGuard locks its {@code FlagRegistry} once its
     * own region data has loaded, which happens before any plugin's {@code onEnable()} runs but
     * after every plugin's {@code onLoad()} has run (Bukkit loads every plugin and calls {@code
     * onLoad()} on each, in dependency order, before calling {@code onEnable()} on any of them) —
     * this is WorldGuard's own documented extension point for registering a custom flag.
     * Registering later throws or is silently ignored depending on WorldGuard's version. Call it
     * unconditionally, regardless of {@code worldguard.respect-regions}: plugin config is not
     * necessarily loaded yet at {@code onLoad()} time, and registering a flag nothing ends up
     * querying is harmless. The exact call: {@code
     * WorldGuardRegionQuery.registerFlag(getLogger())}.
     *
     * <p><strong>Never throws, per the global "startup never fails" constraint.</strong> WorldGuard
     * being absent (no {@code depend}/{@code softdepend} guarantees it is installed), the flag
     * name already being registered by another plugin ({@code FlagRegistry.register} throws a
     * checked {@code FlagConflictException} in that case, which reflection wraps in {@link
     * java.lang.reflect.InvocationTargetException} — itself a {@link
     * ReflectiveOperationException}), or any other reflective/runtime failure is caught and
     * logged here, never propagated. {@link #attemptLink} separately treats "the flag is not
     * present in the registry, or is present under an incompatible type" as its own failure
     * signal (see there) and falls back to {@link RegionQuery#permissive()} — so a failed
     * registration here quietly degrades carpet flight to ignoring WorldGuard entirely rather
     * than ever failing plugin startup or throwing from this method.
     */
    public static void registerFlag(Logger logger) {
        try {
            Class<?> worldGuardClass = Class.forName(WORLDGUARD_CLASS);
            Object worldGuardInstance = worldGuardClass.getMethod("getInstance").invoke(null);

            Object flagRegistry = worldGuardInstance.getClass()
                    .getMethod("getFlagRegistry")
                    .invoke(worldGuardInstance);

            Class<?> stateFlagClass = Class.forName(STATE_FLAG_CLASS);
            Object flag = stateFlagClass.getConstructor(String.class, boolean.class)
                    .newInstance(FLIGHT_FLAG_NAME, true); // true => default State.ALLOW

            Class<?> flagClass = Class.forName(FLAG_CLASS);
            flagRegistry.getClass().getMethod("register", flagClass).invoke(flagRegistry, flag);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (logger != null) {
                logger.log(Level.WARNING,
                        "Could not register the " + FLIGHT_FLAG_NAME + " WorldGuard flag "
                                + "(WorldGuard absent, the name already taken by another plugin, "
                                + "or the registration API has moved); carpet flight will ignore "
                                + "WorldGuard regions", e);
            }
        }
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

            // Look up the flag registerFlag() must already have registered, by name — not
            // Flags.BUILD. If it is absent (registerFlag() never ran, or ran too late — see that
            // method's Javadoc) or was reused by a name conflict with an incompatible flag type,
            // treat that exactly like any other link failure: fall back to permissive.
            Method getFlagRegistryMethod = worldGuardInstance.getClass().getMethod("getFlagRegistry");
            Object flagRegistry = getFlagRegistryMethod.invoke(worldGuardInstance);
            Object flightFlag = flagRegistry.getClass()
                    .getMethod("get", String.class)
                    .invoke(flagRegistry, FLIGHT_FLAG_NAME);
            if (!stateFlagClass.isInstance(flightFlag)) {
                if (logger != null) {
                    logger.log(Level.WARNING,
                            "The " + FLIGHT_FLAG_NAME + " WorldGuard flag is not registered (did "
                                    + "MagicCarpetPlugin.onLoad() call registerFlag()?) or was "
                                    + "reused by an incompatible flag type; carpet flight will "
                                    + "ignore WorldGuard regions");
                }
                return null;
            }

            Class<?> bukkitAdapterClass = Class.forName(BUKKIT_ADAPTER_CLASS);
            Method adaptMethod = bukkitAdapterClass.getMethod("adapt", Location.class);

            return new WorldGuardRegionQuery(
                    getInstanceMethod,
                    getPlatformMethod,
                    getRegionContainerMethod,
                    createQueryMethod,
                    testStateMethod,
                    adaptMethod,
                    flightFlag,
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
     * {@link Method} handles (never re-searching the class for them) and asks whether {@link
     * #FLIGHT_FLAG_NAME} is allowed at the given block. Never throws: any exception here — WorldGuard
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
            Array.set(flags, 0, flightFlag);

            Object result = testStateMethod.invoke(query, weLocation, null, flags);
            return !(result instanceof Boolean allowed) || allowed;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }
}
