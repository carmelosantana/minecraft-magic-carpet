/*
 * MagicCarpet - hand-rolled in-memory ConfigSource fake for tests (no mocking framework).
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A trivial {@link ConfigSource} backed by a {@link Map}, mirroring the "fall back to
 * the default on a missing or mistyped path" contract that {@link BukkitConfigSource}
 * gets for free from {@code FileConfiguration}. Test-only; deliberately not a mock.
 */
final class InMemoryConfigSource implements ConfigSource {

    private final Map<String, Object> values = new HashMap<>();

    /** Stores a raw value under {@code path}, to be read back by whichever getter fits its type. */
    void set(String path, Object value) {
        values.put(path, value);
    }

    @Override
    public String getString(String path, String def) {
        Object value = values.get(path);
        return value == null ? def : String.valueOf(value);
    }

    @Override
    public int getInt(String path, int def) {
        Object value = values.get(path);
        return value instanceof Number number ? number.intValue() : def;
    }

    @Override
    public double getDouble(String path, double def) {
        Object value = values.get(path);
        return value instanceof Number number ? number.doubleValue() : def;
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        Object value = values.get(path);
        return value instanceof Boolean bool ? bool : def;
    }

    @Override
    public List<String> getStringList(String path) {
        Object value = values.get(path);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    @Override
    public boolean isSet(String path) {
        return values.containsKey(path);
    }

    @Override
    public String getRaw(String path) {
        Object value = values.get(path);
        return value == null ? null : String.valueOf(value);
    }
}
