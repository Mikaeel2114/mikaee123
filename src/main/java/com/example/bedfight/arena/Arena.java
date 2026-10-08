package com.example.bedfight.arena;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.DyeColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * An arena = a template world + a box (po1/po2) + team spawns/beds.
 *
 * Block protection model: NOTHING that is part of the map can ever be broken. Only blocks placed by
 * players during the match are breakable, and players can only place blocks inside the box.
 * Below the box's minimum Y is the void (death), the maximum Y is the build height limit.
 */
public final class Arena {
    private final String name;
    private final String worldName;
    private Pos pos1;
    private Pos pos2;
    private Pos spawn;
    private int minX, maxX, minY, maxY, minZ, maxZ;
    private final Map<DyeColor, TeamSpec> teams = new LinkedHashMap<>();

    public Arena(String name, String worldName) {
        this.name = name;
        this.worldName = worldName;
    }

    public String getName() { return name; }
    public String getWorldName() { return worldName; }
    public Pos getPos1() { return pos1; }
    public Pos getPos2() { return pos2; }
    public Pos getSpawn() { return spawn; }
    public void setSpawn(Pos spawn) { this.spawn = spawn; }
    public Map<DyeColor, TeamSpec> getTeams() { return teams; }
    public int getMinX() { return minX; }
    public int getMaxX() { return maxX; }
    public int getMinY() { return minY; }
    public int getMaxY() { return maxY; }
    public int getMinZ() { return minZ; }
    public int getMaxZ() { return maxZ; }

    public void setPos1(Pos p) { this.pos1 = p; recalc(); }
    public void setPos2(Pos p) { this.pos2 = p; recalc(); }
    public boolean hasBounds() { return pos1 != null && pos2 != null; }

    private void recalc() {
        if (!hasBounds()) {
            return;
        }
        minX = Math.min(pos1.blockX(), pos2.blockX());
        maxX = Math.max(pos1.blockX(), pos2.blockX());
        minY = Math.min(pos1.blockY(), pos2.blockY());
        maxY = Math.max(pos1.blockY(), pos2.blockY());
        minZ = Math.min(pos1.blockZ(), pos2.blockZ());
        maxZ = Math.max(pos1.blockZ(), pos2.blockZ());
    }

    /** Is this block inside the build box (where placing is allowed)? */
    public boolean inBuildArea(int x, int y, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= maxY;
    }

    /** Is this horizontal position within the box expanded by padding blocks? Used to limit spectators. */
    public boolean inHorizontalRange(double x, double z, int padding) {
        return x >= minX - padding && x <= maxX + 1 + padding && z >= minZ - padding && z <= maxZ + 1 + padding;
    }

    public TeamSpec team(DyeColor color, boolean create) {
        TeamSpec t = teams.get(color);
        if (t == null && create) {
            t = new TeamSpec(color);
            teams.put(color, t);
        }
        return t;
    }

    /** Returns null when the arena is complete, otherwise a description of what is missing. */
    public String validate() {
        if (!hasBounds()) return "Set both boundaries first (/bedfight po1 and /bedfight po2).";
        if (spawn == null) return "Set the game spawn first (/bedfight setspawn).";
        if (teams.size() < 2) return "At least 2 teams are required (/bedfight createteam <color>).";
        for (TeamSpec t : teams.values()) {
            if (t.getSpawn() == null) return "Team " + t.getColor() + " has no spawn (/bedfight setteam).";
            if (t.getBed() == null) return "Team " + t.getColor() + " has no bed (/bedfight setbed).";
        }
        return null;
    }

    /** Sanity checks for common setup mistakes. Returns null if everything looks fine. */
    public String checkGeometry() {
        if (!hasBounds()) return null;
        if (spawn != null && spawn.blockY() < minY) {
            return "The game spawn is below the void level (min Y = " + minY + ").";
        }
        for (TeamSpec t : teams.values()) {
            if (t.getSpawn() != null && t.getSpawn().blockY() < minY) {
                return "Team " + t.getColor() + " spawn (Y " + t.getSpawn().blockY()
                        + ") is below the void level (min Y = " + minY + "): players would die instantly.";
            }
            if (t.getBed() != null) {
                Pos b = t.getBed();
                if (b.blockX() < minX || b.blockX() > maxX || b.blockZ() < minZ || b.blockZ() > maxZ || b.blockY() < minY) {
                    return "The bed of team " + t.getColor() + " (X " + b.blockX() + ", Y " + b.blockY() + ", Z " + b.blockZ()
                            + ") is outside the po1/po2 box (X " + minX + " to " + maxX + ", Y " + minY + " to " + maxY
                            + ", Z " + minZ + " to " + maxZ + "). Re-run /bedfight po1 and po2 at two opposite corners so the box covers BOTH islands.";
                }
                if (maxY < b.blockY() + 3) {
                    return "Max Y (" + maxY + ") is too low: it must be at least 3 blocks above the bed of team "
                            + t.getColor() + " (Y " + b.blockY() + ") so bed defenses can be built. Set po2 (or po1) higher up.";
                }
            }
        }
        return null;
    }

    public void write(YamlConfiguration y) {
        y.set("name", name);
        y.set("world", worldName);
        pos1.write(y.createSection("pos1"));
        pos2.write(y.createSection("pos2"));
        y.set("bounds.min-x", minX);
        y.set("bounds.max-x", maxX);
        y.set("bounds.min-y", minY);
        y.set("bounds.max-y", maxY);
        y.set("bounds.min-z", minZ);
        y.set("bounds.max-z", maxZ);
        spawn.write(y.createSection("spawn"));
        for (TeamSpec t : teams.values()) {
            String base = "teams." + t.getColor().name();
            t.getSpawn().write(y.createSection(base + ".spawn"));
            t.getBed().write(y.createSection(base + ".bed"));
        }
    }

    /** Reads an arena file. Old "defense" regions from previous versions are ignored. */
    public static Arena read(String name, YamlConfiguration y) {
        Arena a = new Arena(name, y.getString("world", name));
        a.pos1 = Pos.read(y.getConfigurationSection("pos1"));
        a.pos2 = Pos.read(y.getConfigurationSection("pos2"));
        a.recalc();
        a.spawn = Pos.read(y.getConfigurationSection("spawn"));
        ConfigurationSection ts = y.getConfigurationSection("teams");
        if (ts != null) {
            for (String key : ts.getKeys(false)) {
                DyeColor c = Teams.parse(key);
                if (c == null) {
                    continue;
                }
                TeamSpec t = a.team(c, true);
                t.setSpawn(Pos.read(ts.getConfigurationSection(key + ".spawn")));
                t.setBed(Pos.read(ts.getConfigurationSection(key + ".bed")));
            }
        }
        return a;
    }
}
