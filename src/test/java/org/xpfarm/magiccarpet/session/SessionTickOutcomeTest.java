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
        assertEquals(EndReason.NONE, SessionTickOutcome.decide(false, false));
    }

    @Test
    void groundedAloneEndsWithLanded() {
        assertEquals(EndReason.LANDED, SessionTickOutcome.decide(true, false));
    }

    @Test
    void fuelEmptyAloneEndsWithFuelEmpty() {
        assertEquals(EndReason.FUEL_EMPTY, SessionTickOutcome.decide(false, true));
    }

    @Test
    void bothSignalsTogetherPreferLandedOverFuelEmpty() {
        // The documented tie-break: touching ground on the exact tick fuel also empties out
        // must report LANDED, not FUEL_EMPTY, because there is no fall to take damage from.
        assertEquals(EndReason.LANDED, SessionTickOutcome.decide(true, true));
    }
}
