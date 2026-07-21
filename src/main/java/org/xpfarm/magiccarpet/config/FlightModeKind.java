/*
 * MagicCarpet - whether a carpet's rider stands on it or sits on it as a mount.
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
 * The visual/mount style used for carpet flight, configurable separately for Java
 * and Bedrock/Geyser players via {@code flight.java-mode} and {@code flight.bedrock-mode}.
 */
public enum FlightModeKind {
    SEATED,
    STANDING;

    /**
     * Parses a config value case-insensitively against the enum's constant names
     * ({@code "seated"}, {@code "standing"}). Returns {@link Optional#empty()} for
     * any value that does not match, rather than throwing.
     */
    public static Optional<FlightModeKind> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (FlightModeKind kind : values()) {
            if (kind.name().equalsIgnoreCase(value.trim())) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
