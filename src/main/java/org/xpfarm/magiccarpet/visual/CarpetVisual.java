/*
 * MagicCarpet - the dual per-edition carpet visual, driven to the rider's feet each tick.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.visual;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
 * entity) supplies it already spawned, and both visuals are spawned at its location. Neither
 * visual rides anything — {@link #followRider} drives both to the rider's feet every tick; see
 * that method for why carrying them as passengers put the rug around a Bedrock viewer's neck.
 * Neither is ever given a velocity: falling-block velocity does not move on Bedrock at all
 * (Geyser#5655, closed "Can't Fix").
 */
public final class CarpetVisual {

    /** Scoreboard tag applied to both visual entities, matching the mount's tag for orphan sweeps. */
    public static final String VISUAL_TAG = "magiccarpet";

    /**
     * Horizontal scale of the Java {@link BlockDisplay}; the Y scale stays 1 so it stays
     * carpet-thin.
     *
     * <p>Was {@code 3.0f}. Reduced to {@code 1.0f} so both editions render the same size: the
     * Bedrock stand-in is a {@link FallingBlock}, which cannot be scaled at all, so a 3x Java
     * carpet meant Bedrock players saw a rug a third the size of the one Java players saw.
     *
     * <p>It also fixes the centring. A {@link BlockDisplay} renders its block from the origin
     * corner outward, so {@link #TRANSLATE_X}/{@link #TRANSLATE_Z} of {@code -0.5} centre a
     * 1x1 carpet exactly — but left a 3x one spanning {@code -0.5} to {@code 2.5}, centred a
     * full block away from the rider it was supposed to be under.
     */
    public static final float CARPET_SCALE = 1.0f;

    private static final float TRANSLATE_X = -0.5f;

    /**
     * Y translation of the Java display.
     *
     * <p>Zero: {@link #followRider} teleports the visual straight to the rider's feet, so there
     * is no passenger attachment offset left to cancel out. This was {@code -0.6f}, described in
     * its own Javadoc as "a starting estimate, not a measured constant" — an unverifiable number
     * that only ever existed to undo an offset the visual no longer inherits.
     */
    private static final float TRANSLATE_Y = 0f;

    private static final float TRANSLATE_Z = -0.5f;

    /** Full brightness so the carpet does not darken at night regardless of world light level. */
    private static final Display.Brightness FULL_BRIGHTNESS = new Display.Brightness(15, 15);

    private final Plugin plugin;
    private final EditionResolver editionResolver;
    private final BlockDisplay javaVisual;
    private final FallingBlock bedrockVisual;

    /**
     * Spawns both visuals at {@code mount}'s current location. {@code mount} must already be
     * spawned in the world; this constructor does not create it, and the visuals are not
     * attached to it — {@link #followRider} drives them from then on. {@code mount} is used only
     * as the initial placement, so the carpet appears in the right place on the deploy tick
     * rather than at the world origin for one frame.
     */
    public CarpetVisual(Plugin plugin, EditionResolver editionResolver, Entity mount) {
        this.plugin = plugin;
        this.editionResolver = editionResolver;

        Location location = mount.getLocation();
        BlockDisplay java = null;
        FallingBlock bedrock = null;
        try {
            java = spawnJavaVisual(location);
            bedrock = spawnBedrockVisual(location);
        } catch (RuntimeException e) {
            // Partial construction failed: despawn whatever was already spawned before
            // rethrowing, so the caller never has to reason about an orphaned entity.
            // removeIfAlive swallows its own exceptions so cleanup itself can never mask e.
            removeIfAlive(java);
            removeIfAlive(bedrock);
            throw e;
        }
        this.javaVisual = java;
        this.bedrockVisual = bedrock;
    }

    /**
     * Moves both visuals to {@code feet} — the rider's own location, which is their feet.
     * Called every tick by {@code CarpetManager.tickSession}.
     *
     * <h2>Why the visuals are driven, not carried</h2>
     *
     * <p>Both visuals used to ride the mount as passengers. That is why a Bedrock player saw the
     * rug around a Java rider's neck: a passenger sits at the vehicle's attachment point, which
     * is roughly chest height, and only the {@link BlockDisplay} had a correction for it — a
     * {@code TRANSLATE_Y} of {@code -0.6} that its own Javadoc admitted was an uncalibrated
     * estimate. A {@link FallingBlock} has no transformation to correct with, so the Bedrock
     * stand-in got no correction at all.
     *
     * <p>Driving both to the rider's location instead removes the attachment offset from the
     * problem entirely rather than trying to cancel it out. Both editions now render from the
     * same coordinate, so they cannot disagree, and there is no longer a guessed constant that
     * only a play-test could validate. {@code TRANSLATE_Y} is consequently {@code 0}.
     *
     * <p>Smoothness is preserved by {@code setTeleportDuration(1)} on the display (see {@link
     * #spawnJavaVisual}), which interpolates between per-tick teleports the way riding a vehicle
     * used to. Never uses velocity: a {@link FallingBlock}'s velocity does not move it on Bedrock
     * at all (Geyser#5655, closed "Can't Fix").
     *
     * <p>A no-op once either visual is dead, so a teardown racing the tick loop cannot throw.
     */
    public void followRider(Location feet) {
        if (javaVisual.isDead() || bedrockVisual.isDead()) {
            return;
        }
        javaVisual.teleport(feet);
        bedrockVisual.teleport(feet);
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
            // Interpolate between the per-tick teleports in followRider, so the carpet glides
            // instead of stepping. One tick matches the tick cadence that drives it.
            entity.setTeleportDuration(1);
            applyCommonVisualConfig(entity);
        });
    }

    private static FallingBlock spawnBedrockVisual(Location location) {
        return location.getWorld().spawn(location, FallingBlock.class, entity -> {
            entity.setBlockData(Material.RED_CARPET.createBlockData());
            entity.setGravity(false);
            entity.setDropItem(false);
            entity.setHurtEntities(false);
            entity.shouldAutoExpire(false);
            applyCommonVisualConfig(entity);
        });
    }

    /** Configuration shared by both visual entities: hidden by default, non-persistent, tagged. */
    private static void applyCommonVisualConfig(Entity entity) {
        entity.setVisibleByDefault(false);
        entity.setPersistent(false);
        entity.addScoreboardTag(VISUAL_TAG);
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

    /**
     * The UUIDs of both visual entities, regardless of whether they are still alive. Used by
     * {@code CarpetManager.sweepOrphans} to distinguish a live session's own tagged entities
     * from genuinely orphaned ones left behind by a crash.
     */
    public Collection<UUID> entityIds() {
        return List.of(javaVisual.getUniqueId(), bedrockVisual.getUniqueId());
    }

    /**
     * Removes {@code entity} if it is still alive, swallowing any exception {@code remove()}
     * itself throws. Used both by the public {@link #remove()} and by the constructor's
     * partial-failure cleanup path, where a secondary exception here must never replace the
     * original failure the constructor is propagating.
     */
    private static void removeIfAlive(Entity entity) {
        try {
            if (entity != null && !entity.isDead()) {
                entity.remove();
            }
        } catch (RuntimeException ignored) {
            // Cleanup best-effort only: never mask the caller's original exception.
        }
    }
}
