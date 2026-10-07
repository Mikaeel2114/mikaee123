package com.example.bedfight.arena;

import org.bukkit.ChatColor;
import org.bukkit.DyeColor;

import java.util.Locale;

public final class Teams {

    private Teams() {}

    public static DyeColor parse(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (s.equals("LIGHTBLUE") || s.equals("AQUA")) {
            s = "LIGHT_BLUE";
        }
        try {
            return DyeColor.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static ChatColor chat(DyeColor c) {
        switch (c) {
            case WHITE: return ChatColor.WHITE;
            case ORANGE: return ChatColor.GOLD;
            case MAGENTA: return ChatColor.LIGHT_PURPLE;
            case LIGHT_BLUE: return ChatColor.AQUA;
            case YELLOW: return ChatColor.YELLOW;
            case LIME: return ChatColor.GREEN;
            case PINK: return ChatColor.LIGHT_PURPLE;
            case GRAY: return ChatColor.DARK_GRAY;
            case SILVER: return ChatColor.GRAY;
            case CYAN: return ChatColor.DARK_AQUA;
            case PURPLE: return ChatColor.DARK_PURPLE;
            case BLUE: return ChatColor.BLUE;
            case BROWN: return ChatColor.GOLD;
            case GREEN: return ChatColor.DARK_GREEN;
            case RED: return ChatColor.RED;
            case BLACK: return ChatColor.BLACK;
            default: return ChatColor.WHITE;
        }
    }

    public static String display(DyeColor c) {
        String n = c.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return chat(c).toString() + Character.toUpperCase(n.charAt(0)) + n.substring(1) + ChatColor.RESET;
    }
}
