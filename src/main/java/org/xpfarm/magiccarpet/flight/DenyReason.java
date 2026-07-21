/*
 * MagicCarpet - reasons a player may be refused carpet flight, with player-facing text.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.flight;

/**
 * Why {@link FlightGuard#checkDeploy} refused to let a player deploy their carpet.
 *
 * <p>Each constant carries a short, plain-text, player-readable default message with no
 * colour codes or {@code §} formatting — the caller decorates it however it likes.
 */
public enum DenyReason {

    WORLD_DISABLED("Carpet flight is disabled in this world."),
    REGION_DENIED("Carpet flight is not allowed in this region."),
    NO_PERMISSION("You don't have permission to use a magic carpet.");

    private final String message;

    DenyReason(String message) {
        this.message = message;
    }

    /** Short, plain-text, player-facing default message for this reason. */
    public String message() {
        return message;
    }
}
