/*
 * MagicCarpet - world-collision queries about the rider's own hitbox.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.flight;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Answers two questions about a rider's physical situation — "would moving there put them
 * inside terrain?" and "is there ground directly under their feet?" — by asking the world
 * about the rider's <em>actual</em> hitbox rather than sampling a single block position.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Both of the high-severity bugs in the first field report (v0.1.0, 2026-07-22) came from
 * approximating the rider's body with a single point:
 *
 * <ul>
 *   <li>{@code SeatedFlightMode} tested {@code world.getBlockAt(destination).isSolid()} at the
 *       <em>mount's</em> position. The mount is a zero-hitbox marker ArmorStand; the thing that
 *       actually collides is the rider, who hangs below it and is 1.8 blocks tall, spanning two
 *       to three block layers. Flying at a dirt wall, the mount's own block could be air while
 *       the rider's head block was dirt — so the teleport went ahead and the rider suffocated.
 *   <li>{@code CarpetManager} detected landing with {@code player.isOnGround()}. A passenger's
 *       own ground flag does not track ground contact — the vehicle carries that state — so it
 *       stayed false for the whole ride and the carpet never auto-stowed. Confirmed in play:
 *       the rider touched down and stayed seated.
 * </ul>
 *
 * <p>{@link org.bukkit.RegionAccessor#hasCollisionsIn(BoundingBox)} and {@link
 * org.bukkit.entity.Entity#getBoundingBox()} are both present on the pinned {@code paper-api
 * 26.1.2.build.74-stable} (verified with {@code javap}), which means neither approximation is
 * necessary. Asking the world about the rider's real box is exact, is a single call, and —
 * importantly — removes the need to hardcode the rider's offset below the mount. The {@code
 * -0.6} constant in {@code CarpetVisual} is explicitly documented as an uncalibrated estimate;
 * nothing here depends on it being right.
 *
 * <h2>Testability</h2>
 *
 * <p>Every method taking a {@link Player} needs a live world and cannot run outside a server,
 * exactly like the rest of this package. The geometry itself is separated into {@link
 * #groundProbe(BoundingBox, double)}, which is pure {@link BoundingBox} arithmetic and is unit
 * tested directly.
 */
public final class RiderClearance {

    /**
     * How far below the rider's feet the ground probe reaches, in blocks.
     *
     * <p>Must be small enough that hovering a block above the ground is not read as landing, and
     * large enough that the probe reaches strictly <em>inside</em> the block beneath rather than
     * merely touching its top face — a box that only shares a boundary plane with a block is not
     * reliably an intersection.
     */
    private static final double GROUND_PROBE_DEPTH = 0.08;

    /**
     * How far the rider's box is shrunk before a collision test, in blocks.
     *
     * <p>Keeps a box that is merely flush against a wall (or resting exactly on a floor) from
     * reading as a collision, so the carpet can fly close to terrain instead of stopping dead a
     * fraction of a block away from it.
     */
    private static final double CONTACT_TOLERANCE = 0.02;

    private RiderClearance() {
    }

    /**
     * The thin slab immediately beneath {@code riderBox} — same horizontal footprint, extending
     * {@code depth} blocks down from the box's bottom face.
     *
     * <p>Deliberately a slab under the feet rather than the whole box shifted down: shifting the
     * full box down would also intersect any wall the rider is flying alongside, reporting a
     * landing for what is really horizontal contact.
     *
     * <p>Pure geometry, no world access — this is the unit-testable core of {@link
     * #isGrounded(Player)}.
     */
    static BoundingBox groundProbe(BoundingBox riderBox, double depth) {
        double feetY = riderBox.getMinY();
        return new BoundingBox(
                riderBox.getMinX(), feetY - depth, riderBox.getMinZ(),
                riderBox.getMaxX(), feetY, riderBox.getMaxZ());
    }

    /**
     * Whether solid ground sits directly beneath {@code rider}'s feet.
     *
     * <p>This replaces {@code player.isOnGround()} for landing detection. It is correct for a
     * passenger (the seated flight mode's rider), for a natively-flying player (the standing
     * mode's rider), and for a player on foot, because it asks about geometry rather than about
     * a movement flag whose meaning changes depending on whether the player is riding something.
     *
     * <p>Only block collisions count, so a rider over water or lava is not grounded — matching
     * the fact that they would not be standing on it either.
     *
     * @return {@code false} if {@code rider} has no world, rather than guessing
     */
    public static boolean isGrounded(Player rider) {
        World world = rider.getWorld();
        if (world == null) {
            return false;
        }
        return world.hasCollisionsIn(groundProbe(rider.getBoundingBox(), GROUND_PROBE_DEPTH));
    }

    /**
     * Whether {@code rider}'s hitbox would intersect terrain after moving by {@code offset}.
     *
     * @return {@code false} if {@code rider} has no world, rather than guessing
     */
    public static boolean collidesAfterMoving(Player rider, Vector offset) {
        World world = rider.getWorld();
        if (world == null) {
            return false;
        }
        return world.hasCollisionsIn(shrunkBox(rider).shift(offset));
    }

    /**
     * Whether {@code rider} is <em>already</em> inside terrain right now.
     *
     * <p>Used as an escape hatch: a rider who has somehow ended up embedded (a block placed into
     * them, a teleport, a chunk change) must still be allowed to move, or refusing to move them
     * into a solid destination would trap them there permanently — strictly worse than the bug
     * being fixed. Recorded as known limitation A2 pre-release.
     *
     * @return {@code false} if {@code rider} has no world, rather than guessing
     */
    public static boolean isEmbedded(Player rider) {
        World world = rider.getWorld();
        if (world == null) {
            return false;
        }
        return world.hasCollisionsIn(shrunkBox(rider));
    }

    /**
     * {@code rider}'s hitbox pulled in by {@link #CONTACT_TOLERANCE} on every face. {@link
     * org.bukkit.entity.Entity#getBoundingBox()} returns a fresh instance per call, so mutating
     * the result here (both {@code expand} and {@code shift} mutate in place) cannot affect the
     * entity.
     */
    private static BoundingBox shrunkBox(Player rider) {
        return rider.getBoundingBox().expand(-CONTACT_TOLERANCE);
    }
}
