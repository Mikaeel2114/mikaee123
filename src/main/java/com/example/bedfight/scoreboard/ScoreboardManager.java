package com.example.bedfight.scoreboard;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.arena.Teams;
import com.example.bedfight.game.GameManager;
import com.example.bedfight.game.Match;
import com.example.bedfight.game.Mode;
import com.example.bedfight.util.ConfigMerger;
import com.example.bedfight.util.Ping;
import com.example.bedfight.util.Text;
import com.example.bedfight.util.YamlFiles;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Shows the right sidebar for the player's CURRENT state:
 *   in a queue -> queue board, in a match -> match board, spectating -> spectator board, otherwise nothing.
 *
 * The state is re-evaluated on a timer AND immediately (refresh) whenever it changes (joining/leaving the
 * queue, match start/end, spectating), so the board switches right away instead of waiting for the next tick.
 */
public final class ScoreboardManager {
    private final BedFightPlugin plugin;
    private final File file;
    private YamlConfiguration cfg;
    private final Map<UUID, PlayerBoard> boards = new HashMap<>();
    private BukkitTask task;

    public ScoreboardManager(BedFightPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "scoreboard.yml");
        reload();
    }

    public void reload() {
        stop();
        ConfigMerger.merge(plugin, "scoreboard.yml");
        this.cfg = YamlFiles.load(file);
        if (cfg.getBoolean("enabled", true)) {
            long period = Math.max(2L, cfg.getLong("update-ticks", 20L));
            this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, period);
            for (UUID id : plugin.getGameManager() == null ? new ArrayList<UUID>() : new ArrayList<>(plugin.getGameManager().activePlayers())) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    refresh(p);
                }
            }
        }
    }

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
            if (p != null) {
                refresh(p);
            }
        }
        for (UUID id : new ArrayList<>(boards.keySet())) {
            if (!active.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    refresh(p);
                } else {
                    boards.remove(id);
                }
            }
        }
    }

    /** Re-evaluates what this player should see, right now. */
    public void refresh(Player p) {
        if (cfg == null || !cfg.getBoolean("enabled", true) || !p.isOnline()) {
            return;
        }
        GameManager games = plugin.getGameManager();
        Match playing = games.getMatch(p);
        Match spectated = games.getSpectatedMatch(p);

        String title;
        List<String> lines;
        if (playing != null) {
            title = cfg.getString("match.title", "&b&lBedFight");
            lines = matchLines(p, playing, "match.lines");
        } else if (spectated != null) {
            title = cfg.getString("spectator.title", cfg.getString("match.title", "&b&lBedFight"));
            lines = matchLines(p, spectated, "spectator.lines");
        } else if (games.getQueuedMode(p) != null) {
            title = cfg.getString("queue.title", "&b&lBedFight");
            lines = queueLines(p, games);
        } else {
            clear(p);
            return;
        }
        PlayerBoard board = boards.computeIfAbsent(p.getUniqueId(), k -> new PlayerBoard(p));
        board.render(p, Text.color(title), lines);
    }

    /** Removes the plugin's sidebar from the player (e.g. back in the lobby). */
    public void clear(Player p) {
        if (boards.remove(p.getUniqueId()) != null) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /** Forget a player who disconnected. */
    public void remove(Player p) {
        boards.remove(p.getUniqueId());
    }

    private List<String> matchLines(Player p, Match m, String path) {
        Map<String, String> ph = m.placeholders(p);
        ph.put("%ip%", cfg.getString("ip", "minemen.club"));
        List<String> out = new ArrayList<>();
        List<String> raw = cfg.getStringList(path);
        if (raw.isEmpty()) {
            raw = cfg.getStringList("match.lines");
        }
        for (String line : raw) {
            if (line.contains("%teams%")) {
                for (Match.TeamView tv : m.teamViews(p)) {
                    out.add(Text.color(teamLine(tv)));
                }
                continue;
            }
            out.add(Text.color(replace(line, ph)));
        }
        return out;
    }

    private String teamLine(Match.TeamView tv) {
        String name = Teams.name(tv.color());
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
        ph.put("%queue_time%", String.format("%d:%02d", secs / 60L, secs % 60L));
        ph.put("%ping%", String.valueOf(Ping.get(p)));
        ph.put("%ip%", cfg.getString("ip", "minemen.club"));
        List<String> out = new ArrayList<>();
        for (String raw : cfg.getStringList("queue.lines")) {
            out.add(Text.color(replace(raw, ph)));
        }
        return out;
    }

    private static String replace(String s, Map<String, String> ph) {
        for (Map.Entry<String, String> e : ph.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        return s;
    }
}
