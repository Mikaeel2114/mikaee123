package com.example.bedfight.arena;

import com.example.bedfight.BedFightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Handles YML persistence, admin setup sessions, build mode and
 * protection of arena (template) worlds.
 */
public final class ArenaManager {

    private final BedFightPlugin plugin;
    private final File dir;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<UUID, Arena> sessions = new HashMap<>();
    private final Map<UUID, DyeColor> selectedTeam = new HashMap<>();
    private final Set<UUID> buildMode = new HashSet<>();

    public ArenaManager(BedFightPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "arenas");
    }

    // ------------------------------------------------------------ persistence

    public void loadAll() {
        arenas.clear();
        dir.mkdirs();
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            String name = f.getName().substring(0, f.getName().length() - 4);
            Arena a = Arena.read(name, YamlConfiguration.loadConfiguration(f));
            String problem = a.validate();
            if (problem == null) {
                String geo = a.checkGeometry();
                if (geo != null) {
                    plugin.getLogger().warning("Arena '" + name + "' may be misconfigured: " + geo);
                }
                arenas.put(name.toLowerCase(), a);
            } else {
                plugin.getLogger().warning("Skipping incomplete arena '" + name + "': " + problem);
            }
        }
    }

    public Collection<Arena> all() { return arenas.values(); }

    public Arena get(String name) { return arenas.get(name.toLowerCase()); }

    /** @return null on success, otherwise an error message. */
    public String save(Player p) {
        Arena a = sessions.get(p.getUniqueId());
        if (a == null) {
            return "No arena setup in progress. Use /bedfight setuparena <world_name>.";
        }
        String err = a.validate();
        if (err == null) {
            err = a.checkGeometry();
        }
        if (err != null) {
            return err;
        }
        YamlConfiguration y = new YamlConfiguration();
        a.write(y);
        File f = new File(dir, a.getName() + ".yml");
        try {
            dir.mkdirs();
            y.save(f);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save arena " + a.getName(), ex);
            return "Could not write " + f.getName() + ": " + ex.getMessage();
        }
        // register an independent, re-read copy (what is on disk is what is used)
        arenas.put(a.getName().toLowerCase(), Arena.read(a.getName(), y));
        World w = Bukkit.getWorld(a.getWorldName());
        if (w != null) {
            w.save();
        }
        return null;
    }

    // ----------------------------------------------------------- setup session

    /** @return null on success, otherwise an error message. */
    public String setupArena(Player p, String worldName) {
        if (!worldName.matches("[A-Za-z0-9_.\\-]+") || worldName.contains("..")) {
            return "Invalid world name.";
        }
        File container = Bukkit.getWorldContainer();
        File folder = new File(container, worldName);
        if (!folder.isDirectory() || !new File(folder, "level.dat").isFile()) {
            return "World folder '" + worldName + "' (with level.dat) not found in "
                    + container.getAbsolutePath();
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            world = Bukkit.createWorld(new WorldCreator(worldName));
        }
        if (world == null) {
            return "Failed to load world '" + worldName + "'.";
        }

        File existing = new File(dir, worldName + ".yml");
        Arena arena = existing.isFile()
                ? Arena.read(worldName, YamlConfiguration.loadConfiguration(existing))
                : new Arena(worldName, worldName);
        sessions.put(p.getUniqueId(), arena);
        selectedTeam.remove(p.getUniqueId());
        p.teleport(world.getSpawnLocation());
        return null;
    }

    public Arena session(Player p) { return sessions.get(p.getUniqueId()); }

    public void select(Player p, DyeColor c) { selectedTeam.put(p.getUniqueId(), c); }

    public DyeColor selected(Player p) { return selectedTeam.get(p.getUniqueId()); }

    // -------------------------------------------------------------- build mode

    /** @return new state */
    public boolean toggleBuildMode(Player p) {
        if (!buildMode.remove(p.getUniqueId())) {
            buildMode.add(p.getUniqueId());
            return true;
        }
        return false;
    }

    public boolean isBuildMode(Player p) { return buildMode.contains(p.getUniqueId()); }

    public void onQuit(Player p) { buildMode.remove(p.getUniqueId()); }

    // -------------------------------------------------------------- protection

    /** True for saved arena worlds and worlds currently being set up (not for match copies). */
    public boolean isTemplateWorld(World w) {
        String n = w.getName();
        for (Arena a : arenas.values()) {
            if (a.getWorldName().equalsIgnoreCase(n)) {
                return true;
            }
        }
        for (Arena a : sessions.values()) {
            if (a.getWorldName().equalsIgnoreCase(n)) {
                return true;
            }
        }
        return false;
    }
}
