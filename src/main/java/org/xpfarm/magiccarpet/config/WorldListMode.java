/*
 * MagicCarpet - whether worlds.list is treated as an allow-list or a deny-list.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.config;

import java.util.Locale;
import java.util.Optional;

/**
 * How {@code worlds.list} restricts carpet flight: only the listed worlds
 * ({@link #ALLOW_LIST}), or every world except the listed ones ({@link #DENY_LIST}).
 */
public enum WorldListMode {
    ALLOW_LIST,
    DENY_LIST;

    /**
     * Parses a config value case-insensitively against the hyphenated spellings
     * ({@code "allow-list"}, {@code "deny-list"}). Returns {@link Optional#empty()}
     * for any value that does not match, rather than throwing.
     */
    public static Optional<WorldListMode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (WorldListMode mode : values()) {
            if (mode.toString().equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
