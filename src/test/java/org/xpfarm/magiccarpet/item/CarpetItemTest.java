/*
 * MagicCarpet - tests for the server-independent parts of the carpet item.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

/**
 * Covers only the parts of {@link CarpetItem} reachable without a running Bukkit server:
 * marker/recipe key construction and the pure marker-value predicate. {@code create()},
 * {@code isCarpet(ItemStack)} for a non-null stack, and {@code recipe()} all require
 * {@code ItemStack}/{@code ItemMeta} construction, which needs {@code Bukkit.getItemFactory()}
 * and therefore a running server; they are not exercised here.
 */
final class CarpetItemTest {

    @Test
    void markerKeyHasExpectedNamespaceAndName() {
        NamespacedKey key = CarpetItem.markerKey();

        assertEquals("magiccarpet", key.getNamespace());
        assertEquals("carpet", key.getKey());
    }

    @Test
    void recipeKeyHasExpectedNamespaceAndName() {
        NamespacedKey key = CarpetItem.recipeKey();

        assertEquals("magiccarpet", key.getNamespace());
        assertEquals("carpet", key.getKey());
    }

    @Test
    void isCarpetIsFalseForNullStack() {
        assertFalse(CarpetItem.isCarpet(null));
    }

    @Test
    void markerValueIsTrueOnlyForStoredByte() {
        assertTrue(CarpetItem.isMarkerValue((byte) 1));
    }

    @Test
    void markerValueIsFalseForNull() {
        assertFalse(CarpetItem.isMarkerValue(null));
    }

    @Test
    void markerValueIsFalseForOtherByte() {
        assertFalse(CarpetItem.isMarkerValue((byte) 0));
    }
}
