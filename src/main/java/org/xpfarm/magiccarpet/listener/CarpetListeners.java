/*
 * MagicCarpet - wires Bukkit events to CarpetManager: this is what makes jumping fly.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.listener;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Keyed;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.xpfarm.magiccarpet.config.MagicCarpetConfig;
import org.xpfarm.magiccarpet.item.CarpetItem;
import org.xpfarm.magiccarpet.session.CarpetManager;

/**
 * The single {@link Listener} that turns raw Bukkit events into {@link CarpetManager} calls.
 * Every method here is Bukkit-runtime-only (needs a live {@link Player}/{@link Entity}/{@link
 * org.bukkit.inventory.Inventory}), so — per the brief — this class has no unit tests; see the
 * task report for the full "no unit tests, all Bukkit events" reasoning. The two pieces of logic
 * factored out into their own package-private helpers ({@link CombatGraceTracker} and the
 * WorldGuard link in {@link WorldGuardRegionQuery}) are exactly the parts of this task that
 * <em>are</em> separable from the Bukkit runtime, and both are unit-tested directly.
 *
 * <p><strong>Why no defensive {@code try/catch} wrapping, unlike {@code CarpetManager}.</strong>
 * {@code CarpetManager.tick()} wraps every per-player slice because an exception escaping a
 * Bukkit <em>repeating task</em> cancels that task forever, taking every rider down with it.
 * That risk does not apply here: Bukkit's own event dispatcher already isolates one listener
 * method's exception from every other listener and from the event's source (it is caught and
 * logged by Bukkit itself, not propagated). Every branch below is written to be correct by
 * construction instead — null/type guards ({@code instanceof} patterns, which are inherently
 * null-safe) placed before any field access that could otherwise throw — rather than caught
 * after the fact.
 */
public final class CarpetListeners implements Listener {

    private static final String USE_PERMISSION = "magiccarpet.use";
    private static final String CRAFT_PERMISSION = "magiccarpet.craft";

    private final CarpetManager carpetManager;
    private final CombatGraceTracker combatGrace = new CombatGraceTracker();

    private volatile MagicCarpetConfig config;

    public CarpetListeners(CarpetManager carpetManager, MagicCarpetConfig config) {
        this.carpetManager = Objects.requireNonNull(carpetManager, "carpetManager");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Replaces the live configuration this listener reads {@code combat.drop-on-damage} and
     * {@code combat.grace-ticks} from. {@code /carpet reload} (task 10) must call this alongside
     * {@code CarpetManager.applyConfig} — the two objects hold independent config references
     * (this class never reaches into {@code CarpetManager} for its own copy), so a reload that
     * only updated {@code CarpetManager} would leave this listener's combat behaviour stale
     * until restart, the same staleness risk task 3's report already flagged for {@code
     * FlightGuard}. Read fresh (never captured into a local at construction) on every event.
     */
    public void applyConfig(MagicCarpetConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Deploy trigger. Off-hand only, exactly as specified — a carpet held in the main hand must
     * not deploy on jump. Checks are ordered cheapest-and-most-likely-to-reject first, since
     * this fires on every jump of every player on the server, not just carpet users: an already
     * flying rider or a mid-grace-cooldown rider (map lookups) are rejected before the
     * permission check, which is rejected before the off-hand item inspection (the only check
     * that touches item meta/PDC).
     *
     * <p>Every rejection here is silent — no message is sent. A player jumping without holding
     * a permitted carpet is just an ordinary jump; sending "no permission" or "still on
     * cooldown" on literally every jump they take would be constant spam. {@link
     * CarpetManager#deploy} still sends its own denial messages (world/region/permission) for
     * the cases that {@code do} reach it, since those are the direct result of the player
     * deliberately holding a carpet and jumping to use it.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerJump(PlayerJumpEvent event) {
        Player player = event.getPlayer();
        if (carpetManager.hasActiveSession(player)) {
            return;
        }
        if (combatGrace.isActive(player.getUniqueId(), currentTick())) {
            return;
        }
        if (!player.hasPermission(USE_PERMISSION)) {
            return;
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (!CarpetItem.isCarpet(offHand)) {
            return;
        }
        carpetManager.deploy(player);
    }

    /**
     * Cancels any dismount of an active rider that {@code CarpetManager} did not itself just
     * cause. Without this, a seated rider pressing sneak — which vanilla treats as "get off the
     * vehicle" for any mount — would be ejected mid-flight, even though sneak is supposed to
     * mean "descend, stay seated" (see {@code CarpetMotion}). {@link
     * CarpetManager#isTeardownInProgress} is the flag this manager sets around its own
     * mount/anchor removal so that <em>that</em> dismount passes through uncancelled instead of
     * being fought.
     *
     * <p>Relevance check first ({@code instanceof Player}, then the {@code hasActiveSession} map
     * lookup) before touching the teardown flag or the cancellation state, since ordinary
     * vehicle dismounts (horses, boats, minecarts) fire this event constantly across a whole
     * server and are irrelevant here.
     */
    @EventHandler
    public void onEntityDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!carpetManager.hasActiveSession(player)) {
            return;
        }
        if (carpetManager.isTeardownInProgress(player)) {
            return; // Our own dismiss() call caused this; let it through.
        }
        if (event.isCancellable()) {
            event.setCancelled(true);
        }
    }

    /**
     * Drops a rider who takes damage, when {@code combat.drop-on-damage} says to, and starts
     * their {@code combat.grace-ticks} redeploy cooldown (see {@link CombatGraceTracker} and
     * {@link #onPlayerJump}). {@code MONITOR} priority with {@code ignoreCancelled = true}
     * deliberately runs after every other plugin's chance to cancel the damage — a rider should
     * only be dropped for damage that is actually going to apply, not damage another plugin
     * (e.g. a PvP-protection or invulnerability plugin) is about to veto.
     *
     * <p>Relevance check first: {@code instanceof Player} then the {@code hasActiveSession} map
     * lookup, both cheap, before reading config or touching the grace tracker — this event fires
     * for every point of damage to every entity on the server, most of which are not a carpet
     * rider at all.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!carpetManager.hasActiveSession(player)) {
            return;
        }
        MagicCarpetConfig activeConfig = this.config;
        if (!activeConfig.combatDropOnDamage()) {
            return;
        }
        combatGrace.startGrace(player.getUniqueId(), currentTick(), activeConfig.combatGraceTicks());
        carpetManager.dismiss(player, CarpetManager.DismissCause.COMBAT);
    }

    /** Tears down flight and drops the persisted fuel tank on disconnect. */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        carpetManager.dismiss(player, CarpetManager.DismissCause.QUIT);
        carpetManager.clearFuelTank(player);
        combatGrace.clear(player.getUniqueId());
    }

    /** Tears down flight on death. */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!carpetManager.hasActiveSession(player)) {
            return;
        }
        carpetManager.dismiss(player, CarpetManager.DismissCause.DEATH);
    }

    /**
     * Dismisses any session whose mount, anchor, or visual entity is physically in a chunk that
     * is unloading.
     *
     * <p><strong>Exact identity check, not location coincidence.</strong> This fix-pass version
     * replaces an earlier revision that inferred which sessions were affected by comparing each
     * online rider's <em>own</em> current chunk coordinates against the unloading chunk — a
     * proxy that could both false-positive (a rider merely standing at those coordinates, not
     * actually the one whose mount is there) and false-negative (a rider whose mount had drifted
     * a tick behind their own position). {@link CarpetManager#activeSessionEntityOwners()} gives
     * an exact map from every session's tracked entity UUIDs (mount/anchor plus both visual
     * passengers) to the owning player's UUID; this method matches that map against {@link
     * Chunk#getEntities()} — the actual entities physically present in the unloading chunk — by
     * UUID, not by tag or coordinate proximity.
     *
     * <p><strong>Cheap in the overwhelming common case.</strong> This event fires constantly —
     * every chunk that falls outside every player's view distance unloads, the vast majority of
     * the time with nobody flying anywhere on the server. The lookup map is built once per event
     * (never once per session or per candidate entity) and doubles as the early-out: an empty
     * map (no active session at all) returns immediately, before {@link Chunk#getEntities()} is
     * ever called. When sessions do exist, matching is an O(1) {@code Map.get} per entity
     * actually in the chunk, scoped to that one chunk rather than a scan of every session or
     * every online player.
     *
     * <p>{@code DismissCause.ERROR} is used for this teardown cause: none of the eight existing
     * causes is a semantic match for "the world unloaded out from under this session," and this
     * task's brief authorizes only additive, read-only touches to {@code CarpetManager} — not a
     * ninth cause. {@code ERROR}'s own Javadoc — "an isolated bug affecting one rider, not the
     * plugin or server shutting down" — is the closest existing fit: an orphaned mid-flight
     * session from an unloaded chunk is exactly that kind of anomaly, not a normal end-of-flight
     * reason like landing, fuel, combat, a command, a quit, or a death.
     *
     * <p>{@link CarpetManager#dismiss} is idempotent and null-safe, so no redundant lookup is
     * needed to guard against a chunk containing more than one of a single session's entities
     * (e.g. the mount and both visual passengers all unload together, the common case): every
     * match after the first is simply a harmless no-op.
     */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Map<UUID, UUID> entityOwners = carpetManager.activeSessionEntityOwners();
        if (entityOwners.isEmpty()) {
            return; // No active session anywhere; nothing this chunk could possibly contain.
        }
        Chunk chunk = event.getChunk();
        for (Entity entity : chunk.getEntities()) {
            UUID ownerId = entityOwners.get(entity.getUniqueId());
            if (ownerId == null) {
                continue;
            }
            Player rider = Bukkit.getPlayer(ownerId);
            if (rider != null) {
                carpetManager.dismiss(rider, CarpetManager.DismissCause.ERROR);
            }
        }
    }

    /**
     * Denies the carpet recipe's result to a crafter without {@code magiccarpet.craft}.
     *
     * <p>Fires for every crafting-grid change on the server, so the relevance check is the very
     * first thing: {@code instanceof Keyed} is inherently null-safe (covers {@code
     * PrepareItemCraftEvent#getRecipe()} returning {@code null}, which it does whenever the
     * current grid arrangement matches no recipe at all — the overwhelmingly common case while a
     * player is fiddling with a crafting grid) and also safely rejects any non-carpet recipe
     * without ever touching {@code getKey()}, so nothing here can throw for an unrelated or
     * absent recipe. Only once the key actually matches {@link CarpetItem#recipeKey()} does this
     * method touch the viewer's permission and, if it is missing, clear the result.
     */
    @EventHandler
    public void onPrepareItemCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof Keyed keyedRecipe)) {
            return;
        }
        if (!CarpetItem.recipeKey().equals(keyedRecipe.getKey())) {
            return;
        }
        HumanEntity viewer = event.getView().getPlayer();
        if (viewer != null && !viewer.hasPermission(CRAFT_PERMISSION)) {
            event.getInventory().setResult(null);
        }
    }

    private static int currentTick() {
        return Bukkit.getServer().getCurrentTick();
    }
}
