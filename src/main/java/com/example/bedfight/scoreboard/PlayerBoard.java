package com.example.bedfight.scoreboard;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/** One sidebar per player (1.8: 16 chars prefix + 16 chars suffix per line, max 15 lines). */
final class PlayerBoard {
    private static final char SECTION = '\u00A7';
    private final Scoreboard board;
    private final Objective objective;
    private int shown;

    PlayerBoard(Player p) {
        this.board = Bukkit.getScoreboardManager().getNewScoreboard();
        this.objective = board.registerNewObjective("bedfight", "dummy");
        this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
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
        // the new layout has fewer lines than the previous one: remove the leftovers
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
            return new String[] {text, ""};
        }
        String prefix = text.substring(0, 16);
        String rest = text.substring(16);
        if (prefix.charAt(15) == SECTION) {
            // never cut a color code in half
            prefix = prefix.substring(0, 15);
            rest = SECTION + rest;
        }
        // carry the active colors/formatting over into the suffix
        String suffix = cut(ChatColor.getLastColors(prefix) + rest, 16);
        return new String[] {prefix, suffix};
    }
}
