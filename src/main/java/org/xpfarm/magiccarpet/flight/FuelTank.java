/*
 * MagicCarpet - tick-driven fuel gauge tracking how long a player may stay airborne.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.flight;

/**
 * Models one player's carpet fuel as a simple tick-driven state machine.
 *
 * <p>Charge is stored internally as a fractional number of ticks so that recharge —
 * which refills the tank to full over exactly {@code rechargeTicks} regardless of
 * capacity — accumulates smoothly rather than losing sub-tick amounts to rounding.
 * This class has no Bukkit dependency: callers convert config seconds to ticks and
 * drive {@link #drain(int)} / {@link #recharge(int)} once per game tick.
 */
public final class FuelTank {

    private final double capacityTicks;
    private final double rechargeTicks;
    private double charge;

    /**
     * Creates a full tank.
     *
     * @param capacityTicks maximum charge, in ticks; must be positive
     * @param rechargeTicks ticks for a full refill from empty; a non-positive value is
     *     treated as 1 rather than rejected, since it only ever drives a division
     * @throws IllegalArgumentException if {@code capacityTicks} is zero or negative
     */
    public FuelTank(int capacityTicks, int rechargeTicks) {
        if (capacityTicks <= 0) {
            throw new IllegalArgumentException("capacityTicks must be positive: " + capacityTicks);
        }
        this.capacityTicks = capacityTicks;
        this.rechargeTicks = rechargeTicks <= 0 ? 1 : rechargeTicks;
        this.charge = capacityTicks;
    }

    /** Consumes {@code ticks} of charge, clamped at 0. A negative argument is treated as 0. */
    public void drain(int ticks) {
        double amount = Math.max(0, ticks);
        charge = Math.max(0, charge - amount);
    }

    /**
     * Restores charge at the rate needed to fill an empty tank in exactly
     * {@code rechargeTicks}, clamped at capacity. A negative argument is treated as 0.
     */
    public void recharge(int ticks) {
        double amount = Math.max(0, ticks);
        charge = Math.min(capacityTicks, charge + capacityTicks / rechargeTicks * amount);
    }

    /** True once charge has been driven to zero or below. */
    public boolean isEmpty() {
        return charge <= 0;
    }

    /**
     * True when the current charge is below {@code thresholdFraction} of full capacity (a value
     * in {@code [0, 1]}, compared against {@link #fraction()}). Meant for gating a new deploy on
     * "close enough to empty that starting a flight is not worthwhile," which is a strictly
     * broader condition than {@link #isEmpty()} — a threshold above {@code 0.0} also rejects a
     * tank that technically has a token amount of charge left but not enough to be worth the
     * cost of spawning a mount and visuals for.
     */
    public boolean isBelowFraction(double thresholdFraction) {
        return fraction() < thresholdFraction;
    }

    /** Charge as a fraction of capacity, clamped to {@code [0, 1]} for a HUD/gauge. */
    public double fraction() {
        double value = charge / capacityTicks;
        return Math.max(0, Math.min(1, value));
    }

    /** Sets charge to full. */
    public void refill() {
        charge = capacityTicks;
    }
}
