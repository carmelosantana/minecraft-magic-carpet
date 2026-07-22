/*
 * MagicCarpet - tests for typed, validated config loading.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MagicCarpetConfigTest {

    @Test
    void defaultsWhenSourceIsEmpty() {
        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(new InMemoryConfigSource(), warnings::add);

        assertEquals(FlightModeKind.STANDING, config.flightJavaMode());
        assertEquals(FlightModeKind.STANDING, config.flightBedrockMode());
        assertEquals(0.5, config.flightSpeed());
        assertEquals(64, config.flightAltitudeCeiling());
        assertEquals(60, config.fuelCapacitySeconds());
        assertEquals(120, config.fuelRechargeSeconds());
        assertTrue(config.combatDropOnDamage());
        assertEquals(40, config.combatGraceTicks());
        assertEquals(WorldListMode.DENY_LIST, config.worldsMode());
        assertTrue(config.worldsList().isEmpty());
        assertTrue(config.worldguardRespectRegions());
        assertTrue(config.effectsParticles());
        assertTrue(config.effectsSound());
        assertTrue(warnings.isEmpty(), "an empty source must not warn");
    }

    @Test
    void validOverrideIsAppliedForEveryKeyWithNoWarnings() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        // Both modes are set to the OPPOSITE of their defaults on purpose: an override test
        // that happens to assert the default value proves nothing about the key being read.
        source.set("flight.java-mode", "seated");
        source.set("flight.bedrock-mode", "seated");
        source.set("flight.speed", 1.25);
        source.set("flight.altitude-ceiling", 100);
        source.set("fuel.capacity-seconds", 90);
        source.set("fuel.recharge-seconds", 200);
        source.set("combat.drop-on-damage", false);
        source.set("combat.grace-ticks", 20);
        source.set("worlds.mode", "allow-list");
        source.set("worlds.list", List.of("world_nether", "world_the_end"));
        source.set("worldguard.respect-regions", false);
        source.set("effects.particles", false);
        source.set("effects.sound", false);

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(FlightModeKind.SEATED, config.flightJavaMode());
        assertEquals(FlightModeKind.SEATED, config.flightBedrockMode());
        assertEquals(1.25, config.flightSpeed());
        assertEquals(100, config.flightAltitudeCeiling());
        assertEquals(90, config.fuelCapacitySeconds());
        assertEquals(200, config.fuelRechargeSeconds());
        assertFalse(config.combatDropOnDamage());
        assertEquals(20, config.combatGraceTicks());
        assertEquals(WorldListMode.ALLOW_LIST, config.worldsMode());
        assertEquals(List.of("world_nether", "world_the_end"), config.worldsList());
        assertFalse(config.worldguardRespectRegions());
        assertFalse(config.effectsParticles());
        assertFalse(config.effectsSound());
        assertTrue(warnings.isEmpty(), "a fully valid source must not warn");
    }

    @Test
    void outOfRangeFlightSpeedFallsBackToDefaultAndWarnsOnce() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.speed", 5.0);

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(0.5, config.flightSpeed());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("flight.speed"));
        assertTrue(warnings.get(0).contains("5.0"));
    }

    @Test
    void belowRangeFlightSpeedFallsBackToDefaultAndWarnsOnce() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.speed", 0.01);

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(0.5, config.flightSpeed());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("flight.speed"));
    }

    @Test
    void outOfRangeAltitudeCeilingFallsBackToDefaultAndWarnsOnce() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.altitude-ceiling", 1000);

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(64, config.flightAltitudeCeiling());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("flight.altitude-ceiling"));
        assertTrue(warnings.get(0).contains("1000"));
    }

    @Test
    void outOfRangeFuelCapacitySecondsFallsBackToDefaultAndWarnsOnce() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("fuel.capacity-seconds", 4000);

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(60, config.fuelCapacitySeconds());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("fuel.capacity-seconds"));
        assertTrue(warnings.get(0).contains("4000"));
    }

    @Test
    void boundaryValuesAreValidAndDoNotWarn() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.speed", 0.05);
        source.set("flight.altitude-ceiling", 320);
        source.set("fuel.capacity-seconds", 5);
        source.set("fuel.recharge-seconds", 3600);
        source.set("combat.grace-ticks", 0);

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(0.05, config.flightSpeed());
        assertEquals(320, config.flightAltitudeCeiling());
        assertEquals(5, config.fuelCapacitySeconds());
        assertEquals(3600, config.fuelRechargeSeconds());
        assertEquals(0, config.combatGraceTicks());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void unparseableJavaModeFallsBackToDefaultAndWarnsOnce() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.java-mode", "diagonal");

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(FlightModeKind.STANDING, config.flightJavaMode());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("flight.java-mode"));
        assertTrue(warnings.get(0).contains("diagonal"));
    }

    @Test
    void unparseableWorldsModeFallsBackToDefaultAndWarnsOnce() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("worlds.mode", "banana");

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(WorldListMode.DENY_LIST, config.worldsMode());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("worlds.mode"));
        assertTrue(warnings.get(0).contains("banana"));
    }

    @Test
    void enumParsingIsCaseInsensitive() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.java-mode", "SEATED");
        source.set("flight.bedrock-mode", "Seated");
        source.set("worlds.mode", "ALLOW-LIST");

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(FlightModeKind.SEATED, config.flightJavaMode());
        assertEquals(FlightModeKind.SEATED, config.flightBedrockMode());
        assertEquals(WorldListMode.ALLOW_LIST, config.worldsMode());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void warnConsumerReceivesExactlyOneMessagePerInvalidKey() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.speed", -1.0);
        source.set("flight.java-mode", "sideways");
        source.set("combat.grace-ticks", 5000);

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(3, warnings.size());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("flight.speed")));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("flight.java-mode")));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("combat.grace-ticks")));
        // Valid keys elsewhere in the same load are unaffected by the invalid ones.
        assertEquals(0.5, config.flightSpeed());
        assertEquals(FlightModeKind.STANDING, config.flightJavaMode());
        assertEquals(40, config.combatGraceTicks());
    }

    @Test
    void worldsListMissingIsValidAndEmpty() {
        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(new InMemoryConfigSource(), warnings::add);

        assertTrue(config.worldsList().isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void nanFlightSpeedFallsBackToDefaultAndWarnsOnce() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.speed", Double.NaN);

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(0.5, config.flightSpeed());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("flight.speed"));
        assertTrue(warnings.get(0).contains("NaN"));
    }

    @Test
    void positiveInfinityFlightSpeedFallsBackToDefaultAndWarnsOnce() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.speed", Double.POSITIVE_INFINITY);

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(0.5, config.flightSpeed());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("flight.speed"));
        assertTrue(warnings.get(0).contains("Infinity"));
    }

    @Test
    void negativeInfinityFlightSpeedFallsBackToDefaultAndWarnsOnce() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.speed", Double.NEGATIVE_INFINITY);

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(0.5, config.flightSpeed());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("flight.speed"));
        assertTrue(warnings.get(0).contains("-Infinity"));
    }

    @Test
    void nonNumericFlightSpeedFallsBackToDefaultAndWarnsOnce() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.speed", "fast");

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(0.5, config.flightSpeed());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("flight.speed"));
        assertTrue(warnings.get(0).contains("fast"));
    }

    @Test
    void nonNumericAltitudeCeilingFallsBackToDefaultAndWarnsOnce() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.altitude-ceiling", "abc");

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(64, config.flightAltitudeCeiling());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("flight.altitude-ceiling"));
        assertTrue(warnings.get(0).contains("abc"));
    }

    @Test
    void absentAltitudeCeilingUsesDefaultWithNoWarning() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        // flight.altitude-ceiling deliberately left unset.

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(64, config.flightAltitudeCeiling());
        assertTrue(warnings.isEmpty(), "an absent key must not warn");
    }

    @Test
    void keysSetToExactlyTheirDefaultValueDoNotWarn() {
        InMemoryConfigSource source = new InMemoryConfigSource();
        source.set("flight.speed", 0.5);
        source.set("flight.altitude-ceiling", 64);

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig config = MagicCarpetConfig.load(source, warnings::add);

        assertEquals(0.5, config.flightSpeed());
        assertEquals(64, config.flightAltitudeCeiling());
        assertTrue(warnings.isEmpty(), "a key legitimately equal to its default must not warn");
    }

    @Test
    void constructorToleratesNullWorldsList() {
        MagicCarpetConfig config = new MagicCarpetConfig(
                FlightModeKind.SEATED,
                FlightModeKind.STANDING,
                0.5,
                64,
                60,
                120,
                true,
                40,
                WorldListMode.DENY_LIST,
                null,
                true,
                true,
                true);

        assertTrue(config.worldsList().isEmpty());
    }

    @Test
    void worldsListPopulatedIsValidUnderEitherMode() {
        InMemoryConfigSource allowSource = new InMemoryConfigSource();
        allowSource.set("worlds.mode", "allow-list");
        allowSource.set("worlds.list", List.of("world"));

        InMemoryConfigSource denySource = new InMemoryConfigSource();
        denySource.set("worlds.mode", "deny-list");
        denySource.set("worlds.list", List.of("world_nether"));

        List<String> warnings = new ArrayList<>();
        MagicCarpetConfig allowConfig = MagicCarpetConfig.load(allowSource, warnings::add);
        MagicCarpetConfig denyConfig = MagicCarpetConfig.load(denySource, warnings::add);

        assertEquals(List.of("world"), allowConfig.worldsList());
        assertEquals(List.of("world_nether"), denyConfig.worldsList());
        assertTrue(warnings.isEmpty());
    }
}
