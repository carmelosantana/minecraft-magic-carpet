/*
 * MagicCarpet - narrow key/value lookup abstraction over a configuration backend.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.config;

import java.util.List;

/**
 * A narrow view over dotted-path configuration lookup.
 *
 * <p>Exists so that {@link MagicCarpetConfig#load} can be exercised in plain JUnit
 * tests without a running Bukkit server. {@link BukkitConfigSource} adapts a live
 * {@code FileConfiguration}; tests supply a small in-memory fake instead.
 *
 * <p>Every accessor falls back to the supplied default when the path is absent or
 * holds a value of the wrong type. Implementations must not throw for a missing or
 * mistyped path.
 */
public interface ConfigSource {

    /**
     * Returns the string at {@code path}, or {@code def} if absent. Callers that need
     * to distinguish "absent" from "present" pass {@code null} as {@code def}.
     */
    String getString(String path, String def);

    /** Returns the int at {@code path}, or {@code def} if absent or not a number. */
    int getInt(String path, int def);

    /** Returns the double at {@code path}, or {@code def} if absent or not a number. */
    double getDouble(String path, double def);

    /** Returns the boolean at {@code path}, or {@code def} if absent or not a boolean. */
    boolean getBoolean(String path, boolean def);

    /** Returns the string list at {@code path}, or an empty list if absent. */
    List<String> getStringList(String path);
}
