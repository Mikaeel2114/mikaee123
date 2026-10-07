package com.example.bedfight.game;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.arena.Arena;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Matchmaking queues and running matches. */
public final class GameManager {

    private final BedFightPlugin plugin;
    private final Random random = new Random();
    private final Map<Mode, LinkedHashSet<UUID>> queues = new EnumMap<>(Mode.class);
    private final Map<UUID, Mode> queued = new HashMap<>();
    private final Map<UUID, Long> queuedAt = new HashMap<>();
    private final Set<Match> matches = new LinkedHashSet<>();
    private final Map<UUID, Match> byPlayer = new HashMap<>();
    private final Map<String, Match> byWorld = new HashMap<>();

    public GameManager(BedFightPlugin plugin) {
        this.plugin = plugin;
        for (Mode m : Mode.values()) {
            queues.put(m, new LinkedHashSet<>());
        }
    }

    // ------------------------------------------------------------------ lookup

    public Match getMatch(Player p) { return byPlayer.get(p.getUniqueId()); }

    public Match getMatchByWorld(World w) { return byWorld.get(w.getName()); }

    public Mode getQueuedMode(Player p) { return queued.get(p.getUniqueId()); }

    public int getQueueSize(Mode m) { return queues.get(m).size(); }

    public long getQueuedSince(Player p) {
        Long t = queuedAt.get(p.getUniqueId());
        return t == null ? System.currentTimeMillis() : t;
    }

    /** Everyone currently in a queue or a match (used by the scoreboard). */
    public java.util.Set<UUID> activePlayers() {
        java.util.Set<UUID> s = new java.util.HashSet<>(byPlayer.keySet());
        s.addAll(queued.keySet());
        return s;
    }

    public boolean isBusy(Player p) {
        return byPlayer.containsKey(p.getUniqueId()) || queued.containsKey(p.getUniqueId());
    }

    // ------------------------------------------------------------------- queue

    public void joinQueue(Player p, Mode mode) {
        List<Player> group = plugin.getPartyHook().getPartyMembers(p);
        if (group.size() > 1 && !plugin.getPartyHook().isLeader(p)) {
            plugin.msg(p, "Only your party leader can queue the party.");
            return;
        }
        if (group.size() > mode.totalPlayers()) {
            plugin.msg(p, "Your party is too large for " + mode.label() + ".");
            return;
        }
        for (Player member : group) {
            if (isBusy(member)) {
                plugin.msg(p, member.getName() + " is already in a queue or match.");
                return;
            }
        }
        if (plugin.getArenaManager().all().isEmpty()) {
            plugin.msg(p, "No arenas are available right now.");
            return;
        }
        LinkedHashSet<UUID> q = queues.get(mode);
        for (Player member : group) {
            q.add(member.getUniqueId());
            queued.put(member.getUniqueId(), mode);
            queuedAt.put(member.getUniqueId(), System.currentTimeMillis());
            plugin.msg(member, "Joined the " + mode.label() + " queue (" + q.size() + "/"
                    + mode.totalPlayers() + "). Use /bedfight leave to leave.");
        }
        matchmake(mode);
    }

    public boolean leaveQueue(Player p) {
        Mode m = queued.remove(p.getUniqueId());
        queuedAt.remove(p.getUniqueId());
        if (m == null) {
            return false;
        }
        queues.get(m).remove(p.getUniqueId());
        return true;
    }

    private void matchmake(Mode mode) {
        LinkedHashSet<UUID> q = queues.get(mode);
        while (q.size() >= mode.totalPlayers()) {
            List<Arena> arenas = new ArrayList<>(plugin.getArenaManager().all());
            if (arenas.isEmpty()) {
                return;
            }
            List<Player> players = new ArrayList<>();
            Iterator<UUID> it = q.iterator();
            while (it.hasNext() && players.size() < mode.totalPlayers()) {
                UUID id = it.next();
                it.remove();
                queued.remove(id);
                queuedAt.remove(id);
                Player pl = Bukkit.getPlayer(id);
                if (pl != null && pl.isOnline()) {
                    players.add(pl);
                }
            }
            if (players.size() < mode.totalPlayers()) {
                // someone vanished: put the rest back and re-evaluate
                for (Player pl : players) {
                    q.add(pl.getUniqueId());
                    queued.put(pl.getUniqueId(), mode);
                    queuedAt.put(pl.getUniqueId(), System.currentTimeMillis());
                }
                continue;
            }
            startMatch(arenas.get(random.nextInt(arenas.size())), mode, players);
        }
    }

    // ----------------------------------------------------------------- matches

    /** Private match flow: group the party, split into random teams, start. @return error or null. */
    public String startPrivateMatch(Player host, Arena arena, Mode mode) {
        if (!plugin.getPartyHook().isAvailable()) {
            return "The party system (minestormparty) is not available.";
        }
        if (!plugin.getPartyHook().isLeader(host)) {
            return "Only the party leader can start a private match.";
        }
        List<Player> members = plugin.getPartyHook().getPartyMembers(host);
        if (members.size() != mode.totalPlayers()) {
            return "A " + mode.label() + " needs exactly " + mode.totalPlayers()
                    + " players in your party (you have " + members.size() + ").";
        }
        for (Player m : members) {
            if (isBusy(m)) {
                return m.getName() + " is already in a queue or match.";
            }
        }
        startMatch(arena, mode, members);
        return null;
    }

    public Match startMatch(Arena arena, Mode mode, List<Player> players) {
        for (Player p : players) {
            leaveQueue(p);
        }
        List<List<Player>> teams = plugin.getPartyHook().splitIntoTeams(players, mode.teamSize(), 2);
        Match match = new Match(plugin, this, arena, mode, teams);
        register(match);
        match.prepare();
        return match;
    }

    private void register(Match m) {
        matches.add(m);
        byWorld.put(m.getWorldName(), m);
        for (UUID id : m.getPlayerIds()) {
            byPlayer.put(id, m);
        }
    }

    /** Frees a player who left a running match so they can queue again. */
    void detach(Player p) {
        byPlayer.remove(p.getUniqueId());
    }

    void unregister(Match m) {
        matches.remove(m);
        byWorld.remove(m.getWorldName());
        for (UUID id : m.getPlayerIds()) {
            byPlayer.remove(id, m);
        }
    }

    public void shutdown() {
        for (Match m : new ArrayList<>(matches)) {
            m.forceEnd();
        }
    }
}
