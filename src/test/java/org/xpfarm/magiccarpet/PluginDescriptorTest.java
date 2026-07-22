/*
 * MagicCarpet - an enchanted rug you jump onto and fly.
 * Copyright (C) 2026 Carmelo Santana
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * See the LICENSE file at the project root for the full license text.
 */
package org.xpfarm.magiccarpet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses the shipped resource YAML with the same SnakeYAML the server uses.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A malformed {@code plugin.yml} is not a compile error, is not a test failure, and does
 * not fail {@code mvn verify} — the file is copied into the JAR verbatim and only parsed when
 * a real Paper server boots. An earlier revision of this plugin shipped:
 *
 * <pre>description: Enchanted rug carried in the off-hand: jump to unfurl it, ...</pre>
 *
 * <p>The unquoted {@code ": "} inside the value makes SnakeYAML read {@code off-hand: jump} as
 * a nested mapping and throw {@code ScannerException: mapping values are not allowed here}.
 * Paper logged {@code InvalidDescriptionException} and never registered the plugin at all —
 * it was absent from {@code /plugins} rather than present-and-disabled, which is a materially
 * different and more confusing symptom. That defect survived ten implementation tasks, every
 * per-task review, an adversarial whole-branch review, and a green CI run, because nothing in
 * the pipeline ever parsed the file as YAML.
 *
 * <p>These tests close that gap at gate 6 rather than gate 7a.
 */
final class PluginDescriptorTest {

    private static final Path PLUGIN_YML = descriptor("plugin.yml");
    private static final Path CONFIG_YML = descriptor("config.yml");

    /**
     * Prefers the Maven-filtered copy in {@code target/classes} — that is the file that actually
     * ships, and property substitution can inject YAML metacharacters the source file never had.
     * Falls back to the source tree so the test still runs before {@code process-resources}.
     */
    private static Path descriptor(String name) {
        Path filtered = Path.of("target", "classes", name);
        return Files.exists(filtered) ? filtered : Path.of("src", "main", "resources", name);
    }

    private static Map<String, Object> parse(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().load(in);
        }
    }

    @Test
    void pluginYmlIsValidYaml() throws IOException {
        Map<String, Object> parsed = parse(PLUGIN_YML);
        assertNotNull(parsed, "plugin.yml parsed to null — the file is empty or malformed");
    }

    @Test
    void configYmlIsValidYaml() throws IOException {
        Map<String, Object> parsed = parse(CONFIG_YML);
        assertNotNull(parsed, "config.yml parsed to null — the file is empty or malformed");
    }

    @Test
    void pluginYmlDeclaresTheFieldsPaperRequires() throws IOException {
        Map<String, Object> parsed = parse(PLUGIN_YML);

        assertEquals("MagicCarpet", parsed.get("name"));
        assertEquals("org.xpfarm.magiccarpet.MagicCarpetPlugin", parsed.get("main"));
        assertInstanceOf(String.class, parsed.get("api-version"),
                "api-version must be quoted; unquoted it parses as a double and 1.20 becomes 1.2");
        assertEquals("26.1", parsed.get("api-version"));
        assertNotNull(parsed.get("description"), "description is required");

        Object version = parsed.get("version");
        assertNotNull(version, "version is required");
        assertFalse(version.toString().contains("${"),
                "version still holds an unresolved Maven property: " + version);
    }

    @Test
    void pluginYmlDeclaresTheCommandAndItsAlias() throws IOException {
        Map<String, Object> parsed = parse(PLUGIN_YML);

        @SuppressWarnings("unchecked")
        Map<String, Object> commands = (Map<String, Object>) parsed.get("commands");
        assertNotNull(commands, "commands section is required");
        assertTrue(commands.containsKey("carpet"),
                "the carpet command must be declared or getCommand(\"carpet\") returns null");
    }

    @Test
    void pluginYmlDeclaresEveryPermissionTheCodeChecks() throws IOException {
        Map<String, Object> parsed = parse(PLUGIN_YML);

        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) parsed.get("permissions");
        assertNotNull(permissions, "permissions section is required");
        assertTrue(permissions.containsKey("magiccarpet.use"), "magiccarpet.use must be declared");
        assertTrue(permissions.containsKey("magiccarpet.craft"), "magiccarpet.craft must be declared");
        assertTrue(permissions.containsKey("magiccarpet.admin"), "magiccarpet.admin must be declared");
    }

    @Test
    void pluginYmlDeclaresBothSoftDependencies() throws IOException {
        Map<String, Object> parsed = parse(PLUGIN_YML);

        Object softdepend = parsed.get("softdepend");
        assertNotNull(softdepend, "softdepend is required for WorldGuard and Floodgate");
        String declared = softdepend.toString();
        assertTrue(declared.contains("WorldGuard"), "WorldGuard must be a soft dependency");
        assertTrue(declared.contains("floodgate"), "floodgate must be a soft dependency");
    }
}
