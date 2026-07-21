/*
 * MagicCarpet - ConfigSource backed by a live Bukkit FileConfiguration.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.config;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Adapts a Bukkit {@link FileConfiguration} (typically {@code JavaPlugin#getConfig()})
 * to {@link ConfigSource}.
 *
 * <p>Deliberately thin: every method is a one-line delegation to the same-named
 * {@code FileConfiguration} accessor, which already implements the "fall back to the
 * default on a missing or mistyped path" contract {@link ConfigSource} requires. This
 * class cannot be unit tested without a running server, so it is kept obviously correct
 * by construction rather than by test coverage.
 */
public final class BukkitConfigSource implements ConfigSource {

    private final FileConfiguration configuration;

    public BukkitConfigSource(FileConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getString(String path, String def) {
        return configuration.getString(path, def);
    }

    @Override
    public int getInt(String path, int def) {
        return configuration.getInt(path, def);
    }

    @Override
    public double getDouble(String path, double def) {
        return configuration.getDouble(path, def);
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        return configuration.getBoolean(path, def);
    }

    @Override
    public List<String> getStringList(String path) {
        return configuration.getStringList(path);
    }

    @Override
    public boolean isSet(String path) {
        return configuration.isSet(path);
    }

    @Override
    public String getRaw(String path) {
        return configuration.getString(path);
    }
}
