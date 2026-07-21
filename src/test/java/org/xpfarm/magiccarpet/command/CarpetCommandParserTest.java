/*
 * MagicCarpet - tests for the /carpet argument parser.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.xpfarm.magiccarpet.command.CarpetCommandParser.ParsedCommand;
import org.xpfarm.magiccarpet.command.CarpetCommandParser.Subcommand;

final class CarpetCommandParserTest {

    @Test
    void emptyArgsIsHelp() {
        ParsedCommand parsed = CarpetCommandParser.parse(new String[0]);

        assertEquals(Subcommand.HELP, parsed.subcommand());
        assertTrue(parsed.targetName().isEmpty());
    }

    @Test
    void nullArgsIsHelpAndDoesNotThrow() {
        ParsedCommand parsed = CarpetCommandParser.parse(null);

        assertEquals(Subcommand.HELP, parsed.subcommand());
        assertTrue(parsed.targetName().isEmpty());
    }

    @Test
    void giveIsParsedCaseInsensitively() {
        assertEquals(Subcommand.GIVE, CarpetCommandParser.parse(new String[] {"give"}).subcommand());
        assertEquals(Subcommand.GIVE, CarpetCommandParser.parse(new String[] {"GIVE"}).subcommand());
        assertEquals(Subcommand.GIVE, CarpetCommandParser.parse(new String[] {"GiVe"}).subcommand());
    }

    @Test
    void offIsParsedCaseInsensitively() {
        assertEquals(Subcommand.OFF, CarpetCommandParser.parse(new String[] {"off"}).subcommand());
        assertEquals(Subcommand.OFF, CarpetCommandParser.parse(new String[] {"OFF"}).subcommand());
        assertEquals(Subcommand.OFF, CarpetCommandParser.parse(new String[] {"Off"}).subcommand());
    }

    @Test
    void reloadIsParsedCaseInsensitively() {
        assertEquals(Subcommand.RELOAD, CarpetCommandParser.parse(new String[] {"reload"}).subcommand());
        assertEquals(Subcommand.RELOAD, CarpetCommandParser.parse(new String[] {"RELOAD"}).subcommand());
        assertEquals(Subcommand.RELOAD, CarpetCommandParser.parse(new String[] {"ReLoAd"}).subcommand());
    }

    @Test
    void explicitHelpTokenIsParsedCaseInsensitively() {
        assertEquals(Subcommand.HELP, CarpetCommandParser.parse(new String[] {"help"}).subcommand());
        assertEquals(Subcommand.HELP, CarpetCommandParser.parse(new String[] {"HELP"}).subcommand());
    }

    @Test
    void giveWithNoTargetHasEmptyTargetName() {
        ParsedCommand parsed = CarpetCommandParser.parse(new String[] {"give"});

        assertEquals(Subcommand.GIVE, parsed.subcommand());
        assertTrue(parsed.targetName().isEmpty());
    }

    @Test
    void giveWithTargetCapturesTargetName() {
        ParsedCommand parsed = CarpetCommandParser.parse(new String[] {"give", "Steve"});

        assertEquals(Subcommand.GIVE, parsed.subcommand());
        assertEquals("Steve", parsed.targetName().orElseThrow());
    }

    @Test
    void unknownSubcommandIsUnknown() {
        ParsedCommand parsed = CarpetCommandParser.parse(new String[] {"frobnicate"});

        assertEquals(Subcommand.UNKNOWN, parsed.subcommand());
        assertTrue(parsed.targetName().isEmpty());
    }

    @Test
    void extraTrailingArgumentsAreIgnoredForOff() {
        ParsedCommand parsed = CarpetCommandParser.parse(new String[] {"off", "now", "please"});

        assertEquals(Subcommand.OFF, parsed.subcommand());
        assertTrue(parsed.targetName().isEmpty());
    }

    @Test
    void extraTrailingArgumentsAreIgnoredForReload() {
        ParsedCommand parsed = CarpetCommandParser.parse(new String[] {"reload", "extra"});

        assertEquals(Subcommand.RELOAD, parsed.subcommand());
        assertTrue(parsed.targetName().isEmpty());
    }

    @Test
    void extraTrailingArgumentsBeyondTargetAreIgnoredForGive() {
        ParsedCommand parsed = CarpetCommandParser.parse(new String[] {"give", "Steve", "extra", "more"});

        assertEquals(Subcommand.GIVE, parsed.subcommand());
        assertEquals("Steve", parsed.targetName().orElseThrow());
    }

    @Test
    void blankTargetNameIsTreatedAsAbsent() {
        ParsedCommand parsed = CarpetCommandParser.parse(new String[] {"give", "   "});

        assertEquals(Subcommand.GIVE, parsed.subcommand());
        assertTrue(parsed.targetName().isEmpty());
    }

    @Test
    void nullFirstElementIsTreatedAsAbsentAndResolvesToHelp() {
        ParsedCommand parsed = CarpetCommandParser.parse(new String[] {null});

        assertEquals(Subcommand.HELP, parsed.subcommand());
        assertTrue(parsed.targetName().isEmpty());
    }

    @Test
    void emptyStringFirstElementIsTreatedAsAbsentAndResolvesToHelp() {
        ParsedCommand parsed = CarpetCommandParser.parse(new String[] {""});

        assertEquals(Subcommand.HELP, parsed.subcommand());
        assertTrue(parsed.targetName().isEmpty());
    }

    @Test
    void whitespaceOnlyFirstElementIsTreatedAsAbsentAndResolvesToHelp() {
        ParsedCommand parsed = CarpetCommandParser.parse(new String[] {"   "});

        assertEquals(Subcommand.HELP, parsed.subcommand());
        assertTrue(parsed.targetName().isEmpty());
    }

    @Test
    void nullGiveTargetElementIsTreatedAsAbsent() {
        ParsedCommand parsed = CarpetCommandParser.parse(new String[] {"give", null});

        assertEquals(Subcommand.GIVE, parsed.subcommand());
        assertTrue(parsed.targetName().isEmpty());
    }

    @Test
    void parsedCommandRejectsNullSubcommand() {
        assertThrows(NullPointerException.class, () -> new ParsedCommand(null, Optional.empty()));
    }

    @Test
    void parsedCommandRejectsNullTargetName() {
        assertThrows(NullPointerException.class, () -> new ParsedCommand(Subcommand.HELP, null));
    }
}
