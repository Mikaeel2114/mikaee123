package com.example.bedfight.arena;

import org.bukkit.DyeColor;

public final class TeamSpec {
    private final DyeColor color;
    private Pos spawn;
    private Pos bed;

    public TeamSpec(DyeColor color) {
        this.color = color;
    }

    public DyeColor getColor() { return color; }
    public Pos getSpawn() { return spawn; }
    public void setSpawn(Pos spawn) { this.spawn = spawn; }
    public Pos getBed() { return bed; }
    public void setBed(Pos bed) { this.bed = bed; }
}
