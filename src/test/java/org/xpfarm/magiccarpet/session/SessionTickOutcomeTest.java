/*
 * MagicCarpet - tests for the tick-end decision table, the one server-independent piece of
 * the carpet manager's tick body.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.xpfarm.magiccarpet.session.SessionTickOutcome.EndReason;

class SessionTickOutcomeTest {

    @Test
    void neitherSignalContinuesTheSession() {
        assertEquals(EndReason.NONE, SessionTickOutcome.decide(false, false, true));
    }

    @Test
    void groundedAloneEndsWithLanded() {
        assertEquals(EndReason.LANDED, SessionTickOutcome.decide(true, false, true));
    }

    @Test
    void fuelEmptyAloneEndsWithFuelEmpty() {
        assertEquals(EndReason.FUEL_EMPTY, SessionTickOutcome.decide(false, true, true));
    }

    @Test
    void bothSignalsTogetherPreferLandedOverFuelEmpty() {
        // The documented tie-break: touching ground on the exact tick fuel also empties out
        // must report LANDED, not FUEL_EMPTY, because there is no fall to take damage from.
        assertEquals(EndReason.LANDED, SessionTickOutcome.decide(true, true, true));
    }

    @Test
    void groundedBeforeEverLeavingTheGroundDoesNotLand() {
        // Deploy happens from a jump, so the rider is still standing on the ground for the first
        // tick or two. Landing there would end the flight before it began.
        assertEquals(EndReason.NONE, SessionTickOutcome.decide(true, false, false));
    }

    @Test
    void fuelEmptyStillEndsTheSessionBeforeTakeoff() {
        // The takeoff latch gates LANDED only. A rider whose tank empties must always be
        // dismissed, armed or not, or an unlifted session would fly forever on no fuel.
        assertEquals(EndReason.FUEL_EMPTY, SessionTickOutcome.decide(true, true, false));
        assertEquals(EndReason.FUEL_EMPTY, SessionTickOutcome.decide(false, true, false));
    }
}
