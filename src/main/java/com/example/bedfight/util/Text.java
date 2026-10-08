package com.example.bedfight.util;

import org.bukkit.ChatColor;

public final class Text {
    private Text() {}

    /** Translates &-codes. */
    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}
