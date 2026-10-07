package com.example.bedfight.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** UTF-8 safe YAML load/save (the server's default charset may not be UTF-8, e.g. on Windows). */
public final class YamlFiles {

    private YamlFiles() {}

    public static YamlConfiguration load(File file) {
        YamlConfiguration y = new YamlConfiguration();
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            y.load(r);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not read " + file.getName() + ": " + ex.getMessage(), ex);
        }
        return y;
    }

    public static void save(YamlConfiguration y, File file) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), y.saveToString().getBytes(StandardCharsets.UTF_8));
    }
}
