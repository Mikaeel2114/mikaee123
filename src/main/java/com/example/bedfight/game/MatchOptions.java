package com.example.bedfight.game;

import com.example.bedfight.util.Settings;

/** Per-match rules. Public matches use the settings.yml defaults; private matches can change them in the GUI. */
public final class MatchOptions {
    public int respawnDelaySeconds;
    public boolean fallDamage;
    public boolean bedsEnabled = true;
    public boolean allowSpectators = true;
    /** 0 = unlimited. */
    public int timeLimitMinutes;

    public static MatchOptions defaults(Settings s) {
        MatchOptions o = new MatchOptions();
        o.respawnDelaySeconds = Math.max(1, s.getInt("game.respawn-delay-seconds", 5));
        o.fallDamage = s.getBoolean("game.fall-damage", false);
        o.allowSpectators = s.getBoolean("spectator.enabled", true);
        o.timeLimitMinutes = Math.max(0, s.getInt("game.time-limit-minutes", 0));
        return o;
    }

    public MatchOptions copy() {
        MatchOptions o = new MatchOptions();
        o.respawnDelaySeconds = respawnDelaySeconds;
        o.fallDamage = fallDamage;
        o.bedsEnabled = bedsEnabled;
        o.allowSpectators = allowSpectators;
        o.timeLimitMinutes = timeLimitMinutes;
        return o;
    }
}
