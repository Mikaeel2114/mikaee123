package com.example.bedfight.arena;

import org.bukkit.DyeColor;

public final class TeamSpec {
    private final DyeColor color;
    private Pos spawn;
    private Pos bed;

    public TeamSpec(DyeColor color) { this.color = color; }

    public DyeColor getColor() { return color; }
    public Pos getSpawn() { return spawn; }
    public void setSpawn(Pos spawn) { this.spawn = spawn; }
    public Pos getBed() { return bed; }
    public void setBed(Pos bed) { this.bed = bed; }

    private Pos defense1;
    private Pos defense2;

    public Pos getDefense1() { return defense1; }
    public void setDefense1(Pos p) { this.defense1 = p; }
    public Pos getDefense2() { return defense2; }
    public void setDefense2(Pos p) { this.defense2 = p; }
    public boolean hasDefense() { return defense1 != null && defense2 != null; }

    /** True if the block is inside this team's bed-defense region (/beddefend po1 + po2). */
    public boolean inDefense(int x, int y, int z) {
        if (!hasDefense()) {
            return false;
        }
        return x >= Math.min(defense1.blockX(), defense2.blockX()) && x <= Math.max(defense1.blockX(), defense2.blockX())
                && y >= Math.min(defense1.blockY(), defense2.blockY()) && y <= Math.max(defense1.blockY(), defense2.blockY())
                && z >= Math.min(defense1.blockZ(), defense2.blockZ()) && z <= Math.max(defense1.blockZ(), defense2.blockZ());
    }
}
