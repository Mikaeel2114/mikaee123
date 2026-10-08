package com.example.bedfight.util;

import com.example.bedfight.BedFightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** Plays sound lists defined in settings.yml ("SOUND:volume:pitch:delayTicks"). */
public final class Sounds {
    private Sounds() {}

    public static void play(BedFightPlugin plugin, Player p, String settingsKey) {
        if (p == null || !p.isOnline()) {
            return;
        }
        for (String entry : plugin.getSettings().getStringList("sounds." + settingsKey)) {
            String[] parts = entry.trim().split(":");
            Sound sound = parse(parts[0]);
            if (sound == null) {
                continue;
            }
            float volume = number(parts, 1, 1.0F);
            float pitch = number(parts, 2, 1.0F);
            long delay = (long) number(parts, 3, 0F);
            if (delay <= 0) {
                p.playSound(p.getLocation(), sound, volume, pitch);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline()) {
                        p.playSound(p.getLocation(), sound, volume, pitch);
                    }
                }, delay);
            }
        }
    }

    private static Sound parse(String name) {
        try {
            return Sound.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            Bukkit.getLogger().warning("[BedFight] Unknown sound in settings.yml: " + name);
            return null;
        }
    }

    private static float number(String[] parts, int idx, float def) {
        if (parts.length <= idx) {
            return def;
        }
        try {
            return Float.parseFloat(parts[idx].trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
