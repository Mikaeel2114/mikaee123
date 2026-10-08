package com.example.bedfight.game;

import java.util.Locale;

public enum Mode {
    SOLO(1, "1v1"),
    DUO(2, "2v2"),
    TRIO(3, "3v3");

    private final int teamSize;
    private final String label;

    Mode(int teamSize, String label) {
        this.teamSize = teamSize;
        this.label = label;
    }

    public int teamSize() { return teamSize; }
    public String label() { return label; }
    public int totalPlayers() { return teamSize * 2; }

    public static Mode parse(String s) {
        if (s == null) {
            return null;
        }
        String l = s.toLowerCase(Locale.ROOT);
        for (Mode m : values()) {
            if (m.label.equals(l)) {
                return m;
            }
        }
        return null;
    }
}
