package com.example.bedfight.util;

import org.bukkit.entity.Player;

public final class Ping {
    private Ping() {}

    public static int get(Player p) {
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            return Math.max(0, handle.getClass().getField("ping").getInt(handle));
        } catch (Exception ex) {
            return 0;
        }
    }
}
