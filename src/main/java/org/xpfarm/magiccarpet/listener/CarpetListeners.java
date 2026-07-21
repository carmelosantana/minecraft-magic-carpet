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
import java.util.Objects;
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

    /**
     * Deliberately the same literal value as {@code SeatedFlightMode.MOUNT_TAG} / {@code
     * StandingFlightMode.ANCHOR_TAG} / {@code CarpetVisual.VISUAL_TAG} / {@code
     * CarpetManager.ORPHAN_TAG}, so every carpet-related entity is identifiable by the same tag.
     * Not imported from any of those classes, matching the precedent already set between the
     * {@code flight}, {@code visual}, and {@code session} packages: each package that needs this
     * literal declares its own copy rather than creating a cross-package dependency just to
     * share a string constant.
     */
    private static final String CARPET_ENTITY_TAG = "magiccarpet";

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
     * Dismisses any session whose rider is in a chunk that is unloading.
     *
     * <p><strong>Relevance check, cheap first ({@code chunk.getEntities()}).</strong> This event
     * fires constantly — every chunk that falls outside every player's view distance unloads,
     * usually with nothing carpet-related in it at all. {@link Chunk#getEntities()} is scoped to
     * the one chunk in question (not a scan of every session or every entity on the server), so
     * checking it for the {@code magiccarpet} scoreboard tag is the cheap map/set-membership
     * style check the brief calls for: the overwhelmingly common case (no tagged entity in this
     * chunk) returns immediately without ever touching {@code CarpetManager} or iterating online
     * players.
     *
     * <p><strong>Correlating the tagged entity back to a rider.</strong> {@code CarpetManager}
     * exposes no reverse lookup from a mount/anchor entity to the player it belongs to, and this
     * task is authorized to touch {@code CarpetManager} in exactly one place (the teardown flag
     * above) — not to add one. Rather than inventing that lookup, this only runs the second,
     * costlier step once the cheap check above has already found a tagged entity physically
     * present in the unloading chunk: it scans that chunk's world's online players for one whose
     * {@link CarpetManager#hasActiveSession} is true and whose <em>own</em> current chunk
     * coordinates match the unloading chunk's. This is a deliberate proxy for "whose mount is in
     * the unloading chunk" rather than a literal entity-identity check: both {@code
     * SeatedFlightMode}'s mount and {@code StandingFlightMode}'s anchor are teleported to track
     * the rider's own location every tick (the anchor explicitly so — see its Javadoc), so the
     * rider and their mount/anchor share the same chunk in the overwhelming majority of ticks;
     * the anchor lags the player by at most one tick per task 7's own altitude-ceiling note. The
     * residual risk — a rider exactly at a chunk boundary during the one tick their mount and
     * they briefly disagree about which chunk they are in — is flagged for gate 7a runtime
     * verification rather than guessed away.
     *
     * <p>{@code DismissCause.ERROR} is used for this teardown cause: none of the eight existing
     * causes is a semantic match for "the world unloaded out from under this session," and
     * adding a ninth is out of this task's authorized scope for {@code CarpetManager} (only the
     * teardown flag is). {@code ERROR}'s own Javadoc — "an isolated bug affecting one rider, not
     * the plugin or server shutting down" — is the closest existing fit: an orphaned mid-flight
     * session from an unloaded chunk is exactly that kind of anomaly, not a normal end-of-flight
     * reason like landing, fuel, combat, a command, a quit, or a death.
     */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        boolean relevant = false;
        for (Entity entity : chunk.getEntities()) {
            if (entity.getScoreboardTags().contains(CARPET_ENTITY_TAG)) {
                relevant = true;
                break;
            }
        }
        if (!relevant) {
            return;
        }
        for (Player player : chunk.getWorld().getPlayers()) {
            if (!carpetManager.hasActiveSession(player)) {
                continue;
            }
            Chunk riderChunk = player.getLocation().getChunk();
            if (riderChunk.getX() == chunk.getX() && riderChunk.getZ() == chunk.getZ()) {
                carpetManager.dismiss(player, CarpetManager.DismissCause.ERROR);
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
