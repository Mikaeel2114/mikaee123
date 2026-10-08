package com.example.bedfight.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps config files up to date across plugin updates.
 *
 * The default copy shipped inside the jar is compared with the file on disk. Keys that exist in
 * the jar but not on disk are ADDED. Existing keys (and any custom keys) are never touched.
 *
 * - If the missing keys all belong to brand-new top-level sections, the section text (with its
 *   comments) is appended to the end of the file, so the user's own comments are preserved.
 * - Otherwise the missing keys are inserted through the YAML API (this drops comments, so the
 *   previous file is saved as &lt;name&gt;.bak first).
 */
public final class ConfigMerger {
    private ConfigMerger() {}

    public static void merge(JavaPlugin plugin, String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.isFile()) {
            plugin.saveResource(name, false);
            return;
        }
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return;
            }
            String defaultText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.loadFromString(defaultText);
            YamlConfiguration user = YamlFiles.load(file);

            Map<String, Object> missing = new LinkedHashMap<>();
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key) || user.contains(key) || blockedByScalar(user, key)) {
                    continue;
                }
                missing.put(key, defaults.get(key));
            }
            if (missing.isEmpty()) {
                return;
            }

            Set<String> tops = new LinkedHashSet<>();
            boolean allNewSections = true;
            for (String key : missing.keySet()) {
                String top = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
                tops.add(top);
                if (user.contains(top)) {
                    allNewSections = false;
                }
            }

            if (allNewSections) {
                StringBuilder sb = new StringBuilder();
                for (String top : tops) {
                    String block = extractBlock(defaultText, top);
                    if (block == null) {
                        allNewSections = false;
                        break;
                    }
                    sb.append('\n').append(block).append('\n');
                }
                if (allNewSections) {
                    String existing = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    String header = (existing.endsWith("\n") ? "" : "\n")
                            + "\n# --- Added automatically by BedFight "
                            + plugin.getDescription().getVersion() + " ---\n";
                    Files.writeString(file.toPath(), header + sb.toString().stripLeading(),
                            StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    plugin.getLogger().info(name + ": added " + missing.size() + " new option(s): " + String.join(", ", tops));
                    return;
                }
            }

            Files.copy(file.toPath(), new File(plugin.getDataFolder(), name + ".bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            for (Map.Entry<String, Object> e : missing.entrySet()) {
                user.set(e.getKey(), e.getValue());
            }
            YamlFiles.save(user, file);
            plugin.getLogger().info(name + ": added " + missing.size() + " new option(s). Old file saved as " + name + ".bak");
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Could not merge defaults into " + name + ": " + ex.getMessage(), ex);
        }
    }

    /** True when an ancestor of key exists in the user's file as a plain value (e.g. user wrote "a: 5" but default has "a.b"). */
    private static boolean blockedByScalar(YamlConfiguration user, String key) {
        int idx = key.indexOf('.');
        while (idx > 0) {
            String parent = key.substring(0, idx);
            if (user.contains(parent) && !user.isConfigurationSection(parent)) {
                return true;
            }
            idx = key.indexOf('.', idx + 1);
        }
        return false;
    }

    /** Returns the text of a top-level section (plus the comment lines directly above it), or null. */
    private static String extractBlock(String text, String top) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(top + ":")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int from = start;
        while (from > 0 && lines.get(from - 1).startsWith("#")) {
            from--;
        }
        int end = lines.size();
        for (int j = start + 1; j < lines.size(); j++) {
            String l = lines.get(j);
            if (!l.isEmpty() && l.charAt(0) != ' ' && l.charAt(0) != '\t') {
                end = j;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int k = from; k < end; k++) {
            sb.append(lines.get(k)).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
