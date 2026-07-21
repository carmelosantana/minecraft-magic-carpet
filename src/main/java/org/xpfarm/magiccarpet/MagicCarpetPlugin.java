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

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point.
 *
 * <p>Scaffold only. Gate 4 ({@code minecraft-plugin-dev}) wires the real components
 * described in {@code docs/superpowers/specs/2026-07-21-magic-carpet-design.md} §6:
 * the config layer, {@code CarpetItem}, {@code CarpetManager}, the {@code FlightMode}
 * strategies, {@code CarpetVisual}, {@code FuelTank}, {@code FlightGuard}, the
 * listeners, and {@code /carpet}.
 */
public final class MagicCarpetPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("MagicCarpet enabled (scaffold: no behaviour wired yet)");
    }

    @Override
    public void onDisable() {
        getLogger().info("MagicCarpet disabled");
    }
}
