/*
 * MagicCarpet - the dual per-edition carpet visual, attached as passengers of the mount.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.visual;

import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Owns the two visual entities that stand in for "the carpet" for one flight session.
 *
 * <p>Geyser has never spawned Block or Item Display entities for Bedrock clients -
 * {@code JavaAddEntityTranslator} returns early on a null definition (GeyserMC/Geyser#3810,
 * open since 2023) - so a {@link BlockDisplay} is completely invisible to Bedrock players.
 * A {@link FallingBlock} stands in for them instead. Both are spawned {@code
 * setVisibleByDefault(false)} and revealed per viewer by {@link #refreshViewers(Collection)},
 * so exactly one is ever visible to any given player.
 *
 * <p>This class does not spawn the mount; the caller (the flight mode that owns the mount
 * entity, task 6) supplies it already spawned, and both visuals are attached to it as
 * passengers. Neither visual is ever given a velocity: falling-block velocity does not move on
 * Bedrock (Geyser#5655, closed "Can't Fix"), and movement comes entirely from riding the mount.
 */
public final class CarpetVisual {

    /** Scoreboard tag applied to both visual entities, matching the mount's tag for orphan sweeps. */
    public static final String VISUAL_TAG = "magiccarpet";

    /** Horizontal scale of the Java {@link BlockDisplay}; the Y scale stays 1 so it stays carpet-thin. */
    public static final float CARPET_SCALE = 3.0f;

    private static final float TRANSLATE_X = -0.5f;

    /**
     * Y translation correcting the passenger attachment offset (design doc §4): the mount's
     * passenger attachment is index-clamped rather than per-seat, so the rider lands at
     * {@code P - (0, 0.6, 0)} (the player's own vehicle attachment) while the display lands at
     * {@code P}, putting the carpet at the rider's waist without this correction. This value
     * needs in-game calibration - it is a starting estimate, not a measured constant.
     */
    private static final float TRANSLATE_Y = -0.6f;

    private static final float TRANSLATE_Z = -0.5f;

    /** Full brightness so the carpet does not darken at night regardless of world light level. */
    private static final Display.Brightness FULL_BRIGHTNESS = new Display.Brightness(15, 15);

    private final Plugin plugin;
    private final EditionResolver editionResolver;
    private final BlockDisplay javaVisual;
    private final FallingBlock bedrockVisual;

    /**
     * Spawns both visuals at the mount's current location and attaches them to it as
     * passengers. {@code mount} must already be spawned in the world; this constructor does
     * not create it.
     */
    public CarpetVisual(Plugin plugin, EditionResolver editionResolver, Entity mount) {
        this.plugin = plugin;
        this.editionResolver = editionResolver;

        Location location = mount.getLocation();
        this.javaVisual = spawnJavaVisual(location);
        this.bedrockVisual = spawnBedrockVisual(location);

        mount.addPassenger(javaVisual);
        mount.addPassenger(bedrockVisual);
    }

    private static BlockDisplay spawnJavaVisual(Location location) {
        return location.getWorld().spawn(location, BlockDisplay.class, entity -> {
            entity.setBlock(Material.RED_CARPET.createBlockData());
            entity.setTransformation(new Transformation(
                    new Vector3f(TRANSLATE_X, TRANSLATE_Y, TRANSLATE_Z),
                    new Quaternionf(),
                    new Vector3f(CARPET_SCALE, 1f, CARPET_SCALE),
                    new Quaternionf()));
            entity.setDisplayWidth(0f);
            entity.setDisplayHeight(0f);
            entity.setBrightness(FULL_BRIGHTNESS);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setVisibleByDefault(false);
            entity.setPersistent(false);
            entity.addScoreboardTag(VISUAL_TAG);
        });
    }

    private static FallingBlock spawnBedrockVisual(Location location) {
        return location.getWorld().spawn(location, FallingBlock.class, entity -> {
            entity.setBlockData(Material.RED_CARPET.createBlockData());
            entity.setGravity(false);
            entity.setDropItem(false);
            entity.setHurtEntities(false);
            entity.shouldAutoExpire(false);
            entity.setVisibleByDefault(false);
            entity.setPersistent(false);
            entity.addScoreboardTag(VISUAL_TAG);
        });
    }

    /**
     * Applies per-viewer visibility for exactly the supplied viewers: each sees the visual
     * matching their edition and has the other hidden. Safe to call repeatedly with an
     * arbitrary viewer set as players come in and out of range, and a no-op once {@link
     * #remove()} has been called.
     */
    public void refreshViewers(Collection<Player> viewers) {
        if (javaVisual.isDead() || bedrockVisual.isDead()) {
            return;
        }
        for (Player viewer : viewers) {
            if (editionResolver.isBedrock(viewer)) {
                viewer.showEntity(plugin, bedrockVisual);
                viewer.hideEntity(plugin, javaVisual);
            } else {
                viewer.showEntity(plugin, javaVisual);
                viewer.hideEntity(plugin, bedrockVisual);
            }
        }
    }

    /** Despawns both visuals. Idempotent: safe to call more than once or after either has died. */
    public void remove() {
        removeIfAlive(javaVisual);
        removeIfAlive(bedrockVisual);
    }

    private static void removeIfAlive(Entity entity) {
        if (entity != null && !entity.isDead()) {
            entity.remove();
        }
    }
}
