/*
 * MagicCarpet - an enchanted rug you jump onto and fly.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.xpfarm.magiccarpet.command.CarpetCommand;
import org.xpfarm.magiccarpet.config.BukkitConfigSource;
import org.xpfarm.magiccarpet.config.MagicCarpetConfig;
import org.xpfarm.magiccarpet.flight.FlightGuard;
import org.xpfarm.magiccarpet.flight.FlightMode;
import org.xpfarm.magiccarpet.flight.RegionQuery;
import org.xpfarm.magiccarpet.flight.SeatedFlightMode;
import org.xpfarm.magiccarpet.flight.StandingFlightMode;
import org.xpfarm.magiccarpet.item.CarpetItem;
import org.xpfarm.magiccarpet.listener.CarpetListeners;
import org.xpfarm.magiccarpet.listener.WorldGuardRegionQuery;
import org.xpfarm.magiccarpet.session.CarpetManager;
import org.xpfarm.magiccarpet.visual.EditionResolver;

/**
 * Plugin entry point. Wires every component built across tasks 1-9 together: the config
 * layer, {@link CarpetItem}'s recipe, the {@link FlightGuard}/{@link RegionQuery} deploy
 * gate, both {@link FlightMode} strategies, {@link CarpetManager}, {@link CarpetListeners},
 * and {@code /carpet}.
 *
 * <p><strong>{@link #onLoad()} registers the WorldGuard flight flag.</strong> This must
 * happen in {@code onLoad()}, never {@code onEnable()} — WorldGuard locks its flag registry
 * before any plugin's {@code onEnable()} runs, but after every plugin's {@code onLoad()} has
 * run. Registering later degrades silently to "carpet flight ignores WorldGuard regions" for
 * the rest of the server's uptime, with only a single WARNING logged. See {@link
 * WorldGuardRegionQuery#registerFlag(java.util.logging.Logger)}'s Javadoc.
 *
 * <p><strong>Startup never throws.</strong> {@link #onEnable()} wraps its entire body; an
 * unexpected exception is logged at {@code SEVERE} and this plugin disables itself cleanly
 * rather than letting the exception propagate. {@link #onDisable()} tolerates every field
 * still being {@code null} (a partially-failed {@code onEnable()}).
 */
public final class MagicCarpetPlugin extends JavaPlugin {

    private MagicCarpetConfig config;
    private RegionQuery regionQuery;
    private FlightGuard flightGuard;
    private EditionResolver editionResolver;
    private FlightMode seatedFlightMode;
    private FlightMode standingFlightMode;
    private CarpetManager carpetManager;
    private CarpetListeners carpetListeners;
    private BukkitTask tickTask;

    @Override
    public void onLoad() {
        // Hard sequencing requirement: WorldGuard's FlagRegistry is locked before any plugin's
        // onEnable() runs, but every plugin's onLoad() has already run by then. Calling this
        // from onEnable() instead would silently and permanently degrade WorldGuard region
        // checks to permissive for the rest of the server's uptime. Unconditional: config is
        // not loaded yet at this point, and registering an unused flag is harmless.
        WorldGuardRegionQuery.registerFlag(getLogger());
    }

    @Override
    public void onEnable() {
        try {
            enableInternal();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE,
                    "Magic Carpet failed to start; disabling the plugin.", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void enableInternal() {
        saveDefaultConfig();

        this.config = MagicCarpetConfig.load(
                new BukkitConfigSource(getConfig()), message -> getLogger().warning(message));

        this.editionResolver = EditionResolver.create(getLogger());

        this.regionQuery = buildRegionQuery(this.config, getLogger());
        this.flightGuard = new FlightGuard(this.config, this.regionQuery);

        getServer().addRecipe(CarpetItem.recipe());

        this.seatedFlightMode = new SeatedFlightMode();
        this.standingFlightMode = new StandingFlightMode();
        this.carpetManager = new CarpetManager(
                this, this.config, this.flightGuard, this.editionResolver,
                this.seatedFlightMode, this.standingFlightMode);

        this.carpetListeners = new CarpetListeners(this.carpetManager, this.config);
        getServer().getPluginManager().registerEvents(this.carpetListeners, this);

        PluginCommand command = getCommand("carpet");
        if (command != null) {
            CarpetCommand carpetCommand = new CarpetCommand(this.carpetManager, this::reloadConfiguration);
            command.setExecutor(carpetCommand);
            command.setTabCompleter(carpetCommand);
        } else {
            // plugin.yml and this class disagree about the command name; do not let that be
            // a silent failure that only shows up as "unknown command" the first time an
            // admin tries /carpet.
            getLogger().severe("Could not register /carpet: no such command declared in plugin.yml.");
        }

        int swept = this.carpetManager.sweepOrphans();
        getLogger().info("Swept " + swept + " orphaned carpet entities from a previous run.");

        this.tickTask = getServer().getScheduler().runTaskTimer(this, this.carpetManager::tick, 1L, 1L);

        getLogger().info("Magic Carpet enabled.");
    }

    /**
     * A {@code WorldGuardRegionQuery} only when {@code worldguard.respect-regions} is {@code
     * true} (and, transitively through {@link WorldGuardRegionQuery#create}, only when
     * WorldGuard is actually present and its API links); {@link RegionQuery#permissive()}
     * otherwise. {@code WorldGuardRegionQuery.create} must not even be called when the config
     * flag is off, per its own Javadoc contract.
     */
    private static RegionQuery buildRegionQuery(MagicCarpetConfig activeConfig, Logger logger) {
        if (!activeConfig.worldguardRespectRegions()) {
            return RegionQuery.permissive();
        }
        return WorldGuardRegionQuery.create(logger);
    }

    /**
     * {@code /carpet reload}'s {@link CarpetCommand.ConfigReloader}. Re-reads {@code
     * config.yml} from disk (via {@link #reloadConfig()} — this plugin's own configuration
     * only, never {@code Bukkit.reload()} or any other server-wide reload), rebuilds {@link
     * MagicCarpetConfig}, the {@link RegionQuery}, and the {@link FlightGuard} it depends on
     * (the guard holds an immutable config snapshot, so it must be rebuilt, not just handed a
     * new config), then hands the new config and guard to {@link CarpetManager#applyConfig}
     * and the new config to {@link CarpetListeners#applyConfig} so combat/grace behaviour does
     * not go stale. Never throws: any failure is caught, logged, and reported as {@code false}
     * with the previous configuration left fully in effect.
     */
    private boolean reloadConfiguration() {
        try {
            reloadConfig();
            MagicCarpetConfig newConfig = MagicCarpetConfig.load(
                    new BukkitConfigSource(getConfig()), message -> getLogger().warning(message));
            RegionQuery newRegionQuery = buildRegionQuery(newConfig, getLogger());
            FlightGuard newFlightGuard = new FlightGuard(newConfig, newRegionQuery);

            this.carpetManager.applyConfig(newConfig, newFlightGuard);
            this.carpetListeners.applyConfig(newConfig);

            this.config = newConfig;
            this.regionQuery = newRegionQuery;
            this.flightGuard = newFlightGuard;
            return true;
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Failed to reload the Magic Carpet configuration.", e);
            return false;
        }
    }

    @Override
    public void onDisable() {
        try {
            if (this.carpetManager != null) {
                this.carpetManager.shutdownAll();
            }
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Error while tearing down active carpet flights.", e);
        } finally {
            if (this.tickTask != null) {
                this.tickTask.cancel();
                this.tickTask = null;
            }
        }
        getLogger().info("Magic Carpet disabled.");
    }
}
