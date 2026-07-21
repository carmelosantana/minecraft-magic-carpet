/*
 * MagicCarpet - turns /carpet's raw args into a typed command, with zero Bukkit types.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.command;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure parser for {@code /carpet}'s argument array. Deliberately has no dependency on any
 * {@code org.bukkit} type — permission checks, player lookups, and messaging all belong to
 * {@link CarpetCommand}, not here — so this class is exercised by plain JUnit tests with no
 * live server, which is the entire reason the parsing/execution split exists for this task.
 *
 * <p>Never throws for any input, including {@code null} or an empty array, or an array
 * containing a {@code null} or blank element at any position: a {@code null} array and an empty
 * array are both treated as "no subcommand given" and resolve to {@link Subcommand#HELP},
 * matching {@code /carpet} with no arguments; a {@code null} or blank first token is likewise
 * treated as absent and also resolves to {@link Subcommand#HELP}; and a {@code null} or blank
 * second token for {@code give} is treated the same as a missing one — {@link
 * ParsedCommand#targetName()} comes back {@link Optional#empty()} rather than wrapping the
 * {@code null}/blank string or throwing.
 */
public final class CarpetCommandParser {

    private CarpetCommandParser() {
        throw new AssertionError("CarpetCommandParser has no instances");
    }

    /**
     * Parses {@code args} into a {@link ParsedCommand}. Matching on the first token is
     * case-insensitive; any tokens beyond the ones a given subcommand actually uses (a
     * target name for {@code give}, nothing for the others) are ignored rather than causing
     * a parse failure — {@code /carpet off now please} still parses as {@link Subcommand#OFF}.
     * A {@code null} or blank element anywhere in {@code args} is treated as absent rather than
     * dereferenced — see the class Javadoc.
     */
    public static ParsedCommand parse(String[] args) {
        if (args == null || args.length == 0) {
            return new ParsedCommand(Subcommand.HELP, Optional.empty());
        }
        String first = args[0];
        if (first == null || first.isBlank()) {
            return new ParsedCommand(Subcommand.HELP, Optional.empty());
        }
        String token = first.toLowerCase(Locale.ROOT);
        return switch (token) {
            case "give" -> new ParsedCommand(Subcommand.GIVE, targetName(args));
            case "off" -> new ParsedCommand(Subcommand.OFF, Optional.empty());
            case "reload" -> new ParsedCommand(Subcommand.RELOAD, Optional.empty());
            case "help" -> new ParsedCommand(Subcommand.HELP, Optional.empty());
            default -> new ParsedCommand(Subcommand.UNKNOWN, Optional.empty());
        };
    }

    private static Optional<String> targetName(String[] args) {
        if (args.length < 2 || args[1] == null || args[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(args[1]);
    }

    /** Which {@code /carpet} subcommand was parsed. */
    public enum Subcommand {
        /** {@code /carpet give [player]} — admin-only, issues a rug. */
        GIVE,
        /** {@code /carpet off} — stows the sender's own carpet, if any. */
        OFF,
        /** {@code /carpet reload} — admin-only, reloads this plugin's own config. */
        RELOAD,
        /** No subcommand given, or {@code help} explicitly. */
        HELP,
        /** A first token that matches none of the known subcommands. */
        UNKNOWN
    }

    /**
     * The result of parsing {@code /carpet}'s arguments: which {@link Subcommand} was named,
     * and — only meaningful for {@link Subcommand#GIVE} — the target player name, if one was
     * supplied.
     */
    public record ParsedCommand(Subcommand subcommand, Optional<String> targetName) {
        public ParsedCommand {
            Objects.requireNonNull(subcommand, "subcommand");
            Objects.requireNonNull(targetName, "targetName");
        }
    }
}
