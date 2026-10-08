package com.example.bedfight.arena;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

public record Pos(double x, double y, double z, float yaw, float pitch) {

    public static Pos of(Location l) {
        return new Pos(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    public static Pos ofBlock(Block b) {
        return new Pos(b.getX(), b.getY(), b.getZ(), 0.0F, 0.0F);
    }

    public Location toLocation(World w) {
        return new Location(w, x, y, z, yaw, pitch);
    }

    public int blockX() { return (int) Math.floor(x); }
    public int blockY() { return (int) Math.floor(y); }
    public int blockZ() { return (int) Math.floor(z); }

    public void write(ConfigurationSection s) {
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("yaw", (double) yaw);
        s.set("pitch", (double) pitch);
    }

    public static Pos read(ConfigurationSection s) {
        if (s == null || !s.contains("x") || !s.contains("y") || !s.contains("z")) {
            return null;
        }
        return new Pos(s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }
}
