/*
 * MagicCarpet - typed, validated snapshot of the plugin's config.yml.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Immutable, validated configuration for the plugin.
 *
 * <p>Built exclusively through {@link #load(ConfigSource, Consumer)}, which reads every
 * key documented in {@code config.yml} and never throws: a key that is present but
 * unusable (out of range, or an unparseable enum) reports exactly one message to the
 * supplied {@code warn} consumer and falls back to that key's default. A key that is
 * simply absent takes its default silently.
 */
public record MagicCarpetConfig(
        FlightModeKind flightJavaMode,
        FlightModeKind flightBedrockMode,
        double flightSpeed,
        int flightAltitudeCeiling,
        int fuelCapacitySeconds,
        int fuelRechargeSeconds,
        boolean combatDropOnDamage,
        int combatGraceTicks,
        WorldListMode worldsMode,
        List<String> worldsList,
        boolean worldguardRespectRegions,
        boolean effectsParticles,
        boolean effectsSound) {

    private static final FlightModeKind DEFAULT_JAVA_MODE = FlightModeKind.STANDING;
    private static final FlightModeKind DEFAULT_BEDROCK_MODE = FlightModeKind.STANDING;
    private static final double DEFAULT_FLIGHT_SPEED = 0.5;
    private static final double MIN_FLIGHT_SPEED = 0.05;
    private static final double MAX_FLIGHT_SPEED = 2.0;
    private static final int DEFAULT_ALTITUDE_CEILING = 64;
    private static final int MIN_ALTITUDE_CEILING = 8;
    private static final int MAX_ALTITUDE_CEILING = 320;
    private static final int DEFAULT_FUEL_CAPACITY_SECONDS = 60;
    private static final int DEFAULT_FUEL_RECHARGE_SECONDS = 120;
    private static final int MIN_FUEL_SECONDS = 5;
    private static final int MAX_FUEL_SECONDS = 3600;
    private static final boolean DEFAULT_DROP_ON_DAMAGE = true;
    private static final int DEFAULT_GRACE_TICKS = 40;
    private static final int MIN_GRACE_TICKS = 0;
    private static final int MAX_GRACE_TICKS = 1200;
    private static final WorldListMode DEFAULT_WORLDS_MODE = WorldListMode.DENY_LIST;
    private static final boolean DEFAULT_RESPECT_REGIONS = true;
    private static final boolean DEFAULT_PARTICLES = true;
    private static final boolean DEFAULT_SOUND = true;

    /** Defensive copy so the record stays immutable regardless of what the caller passes. */
    public MagicCarpetConfig {
        worldsList = List.copyOf(Objects.requireNonNullElse(worldsList, List.of()));
    }

    /**
     * Reads and validates every config key from {@code source}. A present-but-unusable
     * value (out of range, or an unparseable enum) is reported once through {@code warn}
     * and replaced with that key's default; an absent key silently takes its default.
     * This method never throws.
     */
    public static MagicCarpetConfig load(ConfigSource source, Consumer<String> warn) {
        FlightModeKind javaMode = readEnum(
                source, warn, "flight.java-mode", DEFAULT_JAVA_MODE, FlightModeKind::parse);
        FlightModeKind bedrockMode = readEnum(
                source, warn, "flight.bedrock-mode", DEFAULT_BEDROCK_MODE, FlightModeKind::parse);
        double flightSpeed = readDouble(
                source, warn, "flight.speed", DEFAULT_FLIGHT_SPEED, MIN_FLIGHT_SPEED, MAX_FLIGHT_SPEED);
        int altitudeCeiling = readInt(
                source, warn, "flight.altitude-ceiling", DEFAULT_ALTITUDE_CEILING,
                MIN_ALTITUDE_CEILING, MAX_ALTITUDE_CEILING);
        int fuelCapacitySeconds = readInt(
                source, warn, "fuel.capacity-seconds", DEFAULT_FUEL_CAPACITY_SECONDS,
                MIN_FUEL_SECONDS, MAX_FUEL_SECONDS);
        int fuelRechargeSeconds = readInt(
                source, warn, "fuel.recharge-seconds", DEFAULT_FUEL_RECHARGE_SECONDS,
                MIN_FUEL_SECONDS, MAX_FUEL_SECONDS);
        boolean dropOnDamage = source.getBoolean("combat.drop-on-damage", DEFAULT_DROP_ON_DAMAGE);
        int graceTicks = readInt(
                source, warn, "combat.grace-ticks", DEFAULT_GRACE_TICKS, MIN_GRACE_TICKS, MAX_GRACE_TICKS);
        WorldListMode worldsMode = readEnum(
                source, warn, "worlds.mode", DEFAULT_WORLDS_MODE, WorldListMode::parse);
        List<String> worldsList = source.getStringList("worlds.list");
        boolean respectRegions =
                source.getBoolean("worldguard.respect-regions", DEFAULT_RESPECT_REGIONS);
        boolean particles = source.getBoolean("effects.particles", DEFAULT_PARTICLES);
        boolean sound = source.getBoolean("effects.sound", DEFAULT_SOUND);

        return new MagicCarpetConfig(
                javaMode,
                bedrockMode,
                flightSpeed,
                altitudeCeiling,
                fuelCapacitySeconds,
                fuelRechargeSeconds,
                dropOnDamage,
                graceTicks,
                worldsMode,
                worldsList,
                respectRegions,
                particles,
                sound);
    }

    private static double readDouble(
            ConfigSource source, Consumer<String> warn, String key, double def, double min, double max) {
        double value = source.getDouble(key, def);
        if (!Double.isFinite(value) || value < min || value > max) {
            warn.accept(rangeMessage(key, value, min, max, def));
            return def;
        }
        if (value == def && wrongTypeButPresent(source, key)) {
            warn.accept(typeMessage(key, source.getRaw(key), def));
            return def;
        }
        return value;
    }

    private static int readInt(
            ConfigSource source, Consumer<String> warn, String key, int def, int min, int max) {
        int value = source.getInt(key, def);
        if (value < min || value > max) {
            warn.accept(rangeMessage(key, value, min, max, def));
            return def;
        }
        if (value == def && wrongTypeButPresent(source, key)) {
            warn.accept(typeMessage(key, source.getRaw(key), def));
            return def;
        }
        return value;
    }

    /**
     * True when {@code key} is present in {@code source} but its raw value does not itself
     * parse as a number — i.e. the typed accessor's returned value equaled the default only
     * because it silently substituted it for a wrong-typed value, not because the key is
     * genuinely absent or genuinely set to the default.
     */
    private static boolean wrongTypeButPresent(ConfigSource source, String key) {
        if (!source.isSet(key)) {
            return false;
        }
        String raw = source.getRaw(key);
        return !isParsableAsNumber(raw);
    }

    private static boolean isParsableAsNumber(String raw) {
        if (raw == null) {
            return false;
        }
        try {
            Double.parseDouble(raw);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static <T extends Enum<T>> T readEnum(
            ConfigSource source,
            Consumer<String> warn,
            String key,
            T def,
            Function<String, Optional<T>> parser) {
        String raw = source.getString(key, null);
        if (raw == null) {
            return def;
        }
        Optional<T> parsed = parser.apply(raw);
        if (parsed.isPresent()) {
            return parsed.get();
        }
        warn.accept(enumMessage(key, raw, def));
        return def;
    }

    private static String rangeMessage(String key, Number value, Number min, Number max, Number def) {
        return String.format(
                Locale.ROOT,
                "Invalid value for '%s': %s (must be between %s and %s); using default %s",
                key, value, min, max, def);
    }

    private static String enumMessage(String key, String raw, Object def) {
        return String.format(
                Locale.ROOT,
                "Invalid value for '%s': '%s' (unrecognized); using default '%s'",
                key, raw, def);
    }

    private static String typeMessage(String key, String raw, Object def) {
        return String.format(
                Locale.ROOT,
                "Invalid value for '%s': '%s' (not a number); using default '%s'",
                key, raw, def);
    }
}
