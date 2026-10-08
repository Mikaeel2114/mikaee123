package com.example.bedfight.util;

import com.example.bedfight.BedFightPlugin;
import java.io.File;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/** Wrapper around settings.yml (auto-merged with the defaults shipped in the jar). */
public final class Settings {
    private final BedFightPlugin plugin;
    private final File file;
    private YamlConfiguration cfg;

    public Settings(BedFightPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "settings.yml");
        reload();
    }

    public void reload() {
        ConfigMerger.merge(plugin, "settings.yml");
        this.cfg = YamlFiles.load(file);
    }

    public int getInt(String path, int def) { return cfg.getInt(path, def); }
    public long getLong(String path, long def) { return cfg.getLong(path, def); }
    public double getDouble(String path, double def) { return cfg.getDouble(path, def); }
    public boolean getBoolean(String path, boolean def) { return cfg.getBoolean(path, def); }
    public String getString(String path, String def) { return cfg.getString(path, def); }
    public List<String> getStringList(String path) { return cfg.getStringList(path); }
    public List<Integer> getIntList(String path, List<Integer> def) {
        List<Integer> l = cfg.getIntegerList(path);
        return l.isEmpty() ? def : l;
    }
    public String message(String key, String def) { return Text.color(cfg.getString("messages." + key, def)); }
}
