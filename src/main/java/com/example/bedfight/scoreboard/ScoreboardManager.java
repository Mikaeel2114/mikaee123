package com.example.bedfight.scoreboard;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.arena.Teams;
import com.example.bedfight.game.GameManager;
import com.example.bedfight.game.Match;
import com.example.bedfight.game.Mode;
import com.example.bedfight.util.Ping;
import com.example.bedfight.util.YamlFiles;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Sidebar shown to players in the queue and in a match. Layout lives in scoreboard.yml. */
public final class ScoreboardManager {

    private final BedFightPlugin plugin;
    private final File file;
    private YamlConfiguration cfg;
    private final Map<UUID, PlayerBoard> boards = new HashMap<>();
    private BukkitTask task;

    public ScoreboardManager(BedFightPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "scoreboard.yml");
        if (!file.isFile()) {
            plugin.saveResource("scoreboard.yml", false);
        }
        reload();
    }

    public void reload() {
        stop();
        cfg = YamlFiles.load(file);
        if (cfg.getBoolean("enabled", true)) {
            long period = Math.max(2L, cfg.getLong("update-ticks", 20L));
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, period);
        }
    }

    /** Stops updating and gives every player the default scoreboard back. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : new ArrayList<>(boards.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boards.clear();
    }

    private void tick() {
        GameManager games = plugin.getGameManager();
        Set<UUID> active = games.activePlayers();
        for (UUID id : active) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            Match m = games.getMatch(p);
            String title;
            List<String> lines;
            if (m != null) {
                title = cfg.getString("match.title", "&b&lBedFight");
                lines = matchLines(p, m);
            } else if (games.getQueuedMode(p) != null) {
                title = cfg.getString("queue.title", "&b&lBedFight");
                lines = queueLines(p, games);
            } else {
                continue;
            }
            PlayerBoard board = boards.computeIfAbsent(id, k -> new PlayerBoard(p));
            board.render(p, color(title), lines);
        }
        for (UUID id : new ArrayList<>(boards.keySet())) {
            if (!active.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                }
                boards.remove(id);
            }
        }
    }

    // --------------------------------------------------------------- content

    private List<String> matchLines(Player p, Match m) {
        Map<String, String> ph = m.placeholders(p);
        ph.put("%ip%", cfg.getString("ip", "minemen.club"));
        List<String> out = new ArrayList<>();
        for (String raw : cfg.getStringList("match.lines")) {
            if (raw.contains("%teams%")) {
                for (Match.TeamView tv : m.teamViews(p)) {
                    out.add(color(teamLine(tv)));
                }
            } else {
                out.add(color(replace(raw, ph)));
            }
        }
        return out;
    }

    private String teamLine(Match.TeamView tv) {
        String name = tv.color().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        String status;
        if (tv.bedAlive()) {
            status = cfg.getString("match.bed-alive", "&a\u2714");
        } else if (tv.alive() > 0) {
            status = cfg.getString("match.bed-dead", "&a%alive%").replace("%alive%", String.valueOf(tv.alive()));
        } else {
            status = cfg.getString("match.eliminated", "&c\u2718");
        }
        Map<String, String> ph = new HashMap<>();
        ph.put("%color%", Teams.chat(tv.color()).toString());
        ph.put("%letter%", name.substring(0, 1));
        ph.put("%name%", name);
        ph.put("%team_status%", status);
        ph.put("%you%", tv.mine() ? cfg.getString("match.you", " &7YOU") : "");
        return replace(cfg.getString("match.team-line", "%color%%letter% &f%name%&7: %team_status%%you%"), ph);
    }

    private List<String> queueLines(Player p, GameManager games) {
        Mode mode = games.getQueuedMode(p);
        long secs = Math.max(0L, (System.currentTimeMillis() - games.getQueuedSince(p)) / 1000L);
        Map<String, String> ph = new HashMap<>();
        ph.put("%player%", p.getName());
        ph.put("%mode%", mode.label());
        ph.put("%queued%", String.valueOf(games.getQueueSize(mode)));
        ph.put("%needed%", String.valueOf(mode.totalPlayers()));
        ph.put("%queue_time%", String.format("%d:%02d", secs / 60, secs % 60));
        ph.put("%ping%", String.valueOf(Ping.get(p)));
        ph.put("%ip%", cfg.getString("ip", "minemen.club"));
        List<String> out = new ArrayList<>();
        for (String raw : cfg.getStringList("queue.lines")) {
            out.add(color(replace(raw, ph)));
        }
        return out;
    }

    private static String replace(String s, Map<String, String> ph) {
        for (Map.Entry<String, String> e : ph.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        return s;
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
