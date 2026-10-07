package com.example.bedfight.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;

/**
 * Flicker-free sidebar for 1.8. Each line is an invisible unique entry whose visible text lives in the
 * prefix/suffix of its own team (16 + 16 characters), so a line can be about 32 characters long.
 */
final class PlayerBoard {

    private static final char SECTION = '\u00A7';

    private final Scoreboard board;
    private final Objective objective;
    private int shown;

    PlayerBoard(Player p) {
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective("bedfight", "dummy");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        p.setScoreboard(board);
    }

    void render(Player p, String title, List<String> lines) {
        if (p.getScoreboard() != board) {
            p.setScoreboard(board);
        }
        objective.setDisplayName(cut(title, 32));
        int n = Math.min(lines.size(), 15);
        for (int i = 0; i < n; i++) {
            String entry = entry(i);
            Team team = board.getTeam("l" + i);
            if (team == null) {
                team = board.registerNewTeam("l" + i);
            }
            if (!team.hasEntry(entry)) {
                team.addEntry(entry);
            }
            String[] parts = split(lines.get(i));
            if (!parts[0].equals(team.getPrefix())) {
                team.setPrefix(parts[0]);
            }
            if (!parts[1].equals(team.getSuffix())) {
                team.setSuffix(parts[1]);
            }
            objective.getScore(entry).setScore(n - i);
        }
        for (int i = n; i < shown; i++) {
            board.resetScores(entry(i));
        }
        shown = n;
    }

    private static String entry(int i) {
        return SECTION + Integer.toHexString(i) + SECTION + "r";
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String[] split(String text) {
        text = cut(text, 32);
        if (text.length() <= 16) {
            return new String[]{text, ""};
        }
        String prefix = text.substring(0, 16);
        String rest = text.substring(16);
        if (prefix.charAt(15) == SECTION) { // don't cut a color code in half
            prefix = prefix.substring(0, 15);
            rest = SECTION + rest;
        }
        String suffix = cut(ChatColor.getLastColors(prefix) + rest, 16);
        return new String[]{prefix, suffix};
    }
}
