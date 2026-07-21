/*
 * MagicCarpet - the /carpet command executor and tab completer.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.xpfarm.magiccarpet.command.CarpetCommandParser.ParsedCommand;
import org.xpfarm.magiccarpet.command.CarpetCommandParser.Subcommand;
import org.xpfarm.magiccarpet.item.CarpetItem;
import org.xpfarm.magiccarpet.session.CarpetManager;
import org.xpfarm.magiccarpet.session.CarpetManager.DismissCause;

/**
 * Bukkit-side half of {@code /carpet}: permission checks, player lookups, inventory/session
 * mutation, and messaging. Every argument-shape decision is delegated to {@link
 * CarpetCommandParser}, which has no Bukkit types and is unit tested directly; this class
 * turns a {@link CarpetCommandParser.ParsedCommand} into the actual effect and is not itself
 * unit tested (see the task report — every method here needs a live {@link CommandSender},
 * {@link Player}, or {@link Bukkit#getPlayer(String)}, none of which exist outside a running
 * Paper server, and no mock framework is on the classpath).
 */
public final class CarpetCommand implements CommandExecutor, TabCompleter {

    /** Required for {@code give} and {@code reload}; {@code off} needs no permission node. */
    private static final String ADMIN_PERMISSION = "magiccarpet.admin";

    private static final List<String> SUBCOMMAND_NAMES = List.of("give", "off", "reload", "help");

    private final CarpetManager carpetManager;
    private final ConfigReloader configReloader;

    public CarpetCommand(CarpetManager carpetManager, ConfigReloader configReloader) {
        this.carpetManager = Objects.requireNonNull(carpetManager, "carpetManager");
        this.configReloader = Objects.requireNonNull(configReloader, "configReloader");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ParsedCommand parsed = CarpetCommandParser.parse(args);
        switch (parsed.subcommand()) {
            case GIVE -> handleGive(sender, parsed.targetName());
            case OFF -> handleOff(sender);
            case RELOAD -> handleReload(sender);
            case HELP, UNKNOWN -> sendHelp(sender);
        }
        return true;
    }

    private void handleGive(CommandSender sender, Optional<String> targetName) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("You do not have permission to do that.");
            return;
        }

        Player target;
        if (targetName.isPresent()) {
            target = Bukkit.getPlayer(targetName.get());
            if (target == null) {
                sender.sendMessage("Player " + targetName.get() + " is not online.");
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage("Console has no inventory. Specify a player: /carpet give <player>");
            return;
        }

        giveRug(target);

        if (sender.equals(target)) {
            sender.sendMessage("You received a carpet rug.");
        } else {
            sender.sendMessage("Gave a carpet rug to " + target.getName() + ".");
            target.sendMessage("You received a carpet rug.");
        }
    }

    private void giveRug(Player target) {
        ItemStack rug = CarpetItem.create();
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(rug);
        if (overflow.isEmpty()) {
            return;
        }
        World world = target.getWorld();
        for (ItemStack leftover : overflow.values()) {
            world.dropItemNaturally(target.getLocation(), leftover);
        }
    }

    private void handleOff(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /carpet off.");
            return;
        }
        if (!carpetManager.hasActiveSession(player)) {
            player.sendMessage("You don't have an active carpet flight.");
            return;
        }
        carpetManager.dismiss(player, DismissCause.COMMAND);
        player.sendMessage("Your carpet has been stowed.");
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("You do not have permission to do that.");
            return;
        }
        boolean success;
        try {
            success = configReloader.reload();
        } catch (RuntimeException e) {
            // ConfigReloader's contract says it must never throw, but that is documentation,
            // not enforcement: an unguarded call here would leak a raw stack trace through
            // Bukkit's generic command-error path instead of this command's own clean failure
            // message. Every other exception boundary in this codebase (CarpetManager.deploy,
            // tick, shutdownAll) defensively wraps a documented-safe call the same way.
            Bukkit.getLogger().log(Level.WARNING,
                    "Magic Carpet: configReloader.reload() threw despite its contract; treating"
                            + " as a failed reload for " + sender.getName(), e);
            success = false;
        }
        if (success) {
            sender.sendMessage("Magic Carpet configuration reloaded.");
        } else {
            sender.sendMessage("Failed to reload the Magic Carpet configuration. Check the console.");
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("Magic Carpet commands:");
        sender.sendMessage("/carpet give [player] - give a carpet rug (admin)");
        sender.sendMessage("/carpet off - stow your carpet");
        sender.sendMessage("/carpet reload - reload the configuration (admin)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args == null || args.length == 0) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return matchingSubcommands(sender, args[0]);
        }
        if (args.length == 2 && "give".equalsIgnoreCase(args[0]) && sender.hasPermission(ADMIN_PERMISSION)) {
            return matchingPlayerNames(args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> matchingSubcommands(CommandSender sender, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String name : SUBCOMMAND_NAMES) {
            if (!name.startsWith(lowerPrefix)) {
                continue;
            }
            if (requiresAdmin(name) && !sender.hasPermission(ADMIN_PERMISSION)) {
                continue;
            }
            matches.add(name);
        }
        return matches;
    }

    private static boolean requiresAdmin(String subcommandName) {
        return "give".equals(subcommandName) || "reload".equals(subcommandName);
    }

    private List<String> matchingPlayerNames(String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            String name = online.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                matches.add(name);
            }
        }
        return matches;
    }

    /**
     * Rebuilds this plugin's own configuration and hands the result to {@link CarpetManager}.
     *
     * <p>Defined here, implemented by whichever task wires {@code MagicCarpetPlugin} together
     * (task 10), because reloading needs to re-read {@code config.yml}, rebuild a {@code
     * MagicCarpetConfig} and a fresh {@code FlightGuard} (it holds an immutable config
     * snapshot — see {@link CarpetManager#applyConfig}), and pass both to {@code
     * CarpetManager.applyConfig(...)} together. This command deliberately does not reach into
     * the plugin's internals to do that itself — it only knows about {@link CarpetManager} and
     * this one small callback.
     *
     * <p><strong>Contract:</strong> must never throw. A failure reading or validating the new
     * config (or applying it) must be caught internally, logged, and reported back as {@code
     * false} — matching the "startup never throws" rule this plugin applies everywhere else.
     * Must never call {@code Bukkit.reload()}, {@code Bukkit.reloadData()}, {@code
     * PluginManager#disablePlugin}/{@code enablePlugin}, or any other server-wide reload; this
     * reloads only this plugin's own configuration.
     */
    @FunctionalInterface
    public interface ConfigReloader {
        /** @return {@code true} if the reload succeeded, {@code false} if it failed */
        boolean reload();
    }
}
