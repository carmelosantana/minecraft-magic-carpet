/*
 * MagicCarpet - pure decision table for whether a flight session ends this tick, and why.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.session;

/**
 * Given this tick's ground-contact and fuel-exhaustion signals, decides whether {@code
 * CarpetManager}'s tick body should end the session and, if so, which {@code DismissCause} it
 * ends with. Package-private and free of any Bukkit dependency so this one piece of {@code
 * CarpetManager}'s tick body — otherwise entirely server-runtime code — is unit testable.
 *
 * <p>The altitude ceiling is deliberately not an input here: exceeding it clamps movement
 * (via {@code FlightGuard.clampAltitude}, already tested in task 3) rather than ending the
 * session, so it plays no part in this decision.
 *
 * <p><strong>Tie-break:</strong> when both signals are true in the same tick — the fuel gauge
 * crosses to empty on exactly the tick the player also touches down — ground contact wins.
 * Landing is the safe, damage-free ending the design calls for, and once the player is
 * already on the ground there is no fall left to take damage from, so reporting {@code
 * LANDED} rather than {@code FUEL_EMPTY} in that tie is strictly more accurate. It also never
 * softens the real fuel-exhaustion case this rule exists to protect: that case is airborne by
 * definition ({@code grounded} is false), so the fall-and-take-damage consequence is untouched.
 */
final class SessionTickOutcome {

    enum EndReason {
        NONE,
        LANDED,
        FUEL_EMPTY
    }

    private SessionTickOutcome() {
    }

    static EndReason decide(boolean grounded, boolean fuelEmpty) {
        if (grounded) {
            return EndReason.LANDED;
        }
        if (fuelEmpty) {
            return EndReason.FUEL_EMPTY;
        }
        return EndReason.NONE;
    }
}
