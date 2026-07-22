/*
 * MagicCarpet - the rug item held in either hand, and its crafting recipe.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.item;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Builds the magic carpet item: a marked {@link Material#RED_CARPET} that {@code CarpetManager}
 * (a later task) recognizes when a player holds it in either hand.
 *
 * <p>The item is identified by a byte marker under {@link #MARKER_KEY} in its persistent data
 * container rather than by display name or lore, so renaming the item in an anvil does not
 * strip its function.
 */
public final class CarpetItem {

    private static final String NAMESPACE = "magiccarpet";
    private static final String MARKER_KEY_NAME = "carpet";
    private static final String RECIPE_KEY_NAME = "carpet";
    private static final byte MARKER_VALUE = (byte) 1;

    /** Namespaced key of the persistent-data marker that identifies a carpet item stack. */
    public static final NamespacedKey MARKER_KEY = markerKey();

    private CarpetItem() {
    }

    /**
     * Which hand {@code inventory} is holding a carpet in, preferring the off-hand, or {@code
     * null} if neither hand holds one.
     *
     * <h2>Why either hand</h2>
     *
     * <p>The design originally required the off-hand, so the main hand stayed free for tools and
     * weapons. That turns out to be unreachable on Bedrock: the Bedrock client permits only a
     * fixed set of items in the off-hand slot (shields, totems, maps, arrows, fireworks) and a
     * carpet will never be one of them. GeyserMC/Geyser#2057 is closed as "Can't Fix" — it is a
     * client restriction, not something a server plugin or a Geyser update can lift. Off-hand-only
     * therefore meant Bedrock players could never fly at all, which the first Bedrock reports
     * confirmed.
     *
     * <p>The off-hand is still preferred when both hands hold a carpet, so a Java player who keeps
     * one in the off-hand sees no change in behaviour.
     */
    public static EquipmentSlot findHeldCarpet(PlayerInventory inventory) {
        if (isCarpet(inventory.getItemInOffHand())) {
            return EquipmentSlot.OFF_HAND;
        }
        if (isCarpet(inventory.getItemInMainHand())) {
            return EquipmentSlot.HAND;
        }
        return null;
    }

    /**
     * Builds the {@link NamespacedKey} used as the carpet marker. Split out from field
     * initialization so the namespace/key strings are independently testable without touching
     * any other Bukkit API.
     */
    static NamespacedKey markerKey() {
        return new NamespacedKey(NAMESPACE, MARKER_KEY_NAME);
    }

    /**
     * Pure check for whether a marker byte value read from a {@link PersistentDataContainer}
     * represents a carpet. Kept separate from {@link #isCarpet(ItemStack)} so this branch of
     * the logic is testable without constructing an {@link ItemStack}.
     */
    static boolean isMarkerValue(Byte value) {
        return value != null && value == MARKER_VALUE;
    }

    /**
     * Builds a fresh magic carpet item: a red carpet with a display name, lore describing the
     * controls, an enchantment glint, and the persistent-data marker.
     */
    public static ItemStack create() {
        ItemStack item = new ItemStack(Material.RED_CARPET);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(
                Component.text("Magic Carpet").decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                plainLoreLine("Hold in either hand."),
                plainLoreLine("Jump to fly."),
                plainLoreLine("Sneak to descend."),
                plainLoreLine("Land to stow it, or /carpet off.")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(MARKER_KEY, PersistentDataType.BYTE, MARKER_VALUE);

        item.setItemMeta(meta);
        return item;
    }

    private static Component plainLoreLine(String text) {
        return Component.text(text).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Returns true only when {@code stack} is non-null, has meta, and carries the carpet marker
     * in its persistent data container. Never throws, including for a null stack, an air stack,
     * or a stack with no meta.
     */
    public static boolean isCarpet(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return false;
        }
        if (!stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Byte value = pdc.get(MARKER_KEY, PersistentDataType.BYTE);
        return isMarkerValue(value);
    }

    /** Namespaced key under which the carpet's crafting recipe is registered. */
    public static NamespacedKey recipeKey() {
        return new NamespacedKey(NAMESPACE, RECIPE_KEY_NAME);
    }

    /**
     * Builds the shaped recipe for the carpet: red wool across the middle row, a phantom
     * membrane above the centre, and a gold ingot below it.
     */
    public static ShapedRecipe recipe() {
        ShapedRecipe recipe = new ShapedRecipe(recipeKey(), create());
        recipe.shape(" P ", "WWW", " G ");
        recipe.setIngredient('P', Material.PHANTOM_MEMBRANE);
        recipe.setIngredient('W', Material.RED_WOOL);
        recipe.setIngredient('G', Material.GOLD_INGOT);
        return recipe;
    }
}
