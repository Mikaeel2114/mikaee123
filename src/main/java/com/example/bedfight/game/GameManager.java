package com.example.bedfight.game;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.arena.Arena;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class GameManager {
    private final BedFightPlugin plugin;
    private final Random random = new Random();
    private final Map<Mode, LinkedHashSet<UUID>> queues = new EnumMap<>(Mode.class);
    private final Map<UUID, Mode> queued = new HashMap<>();
    private final Map<UUID, Long> queuedAt = new HashMap<>();
    private final Set<Match> matches = new LinkedHashSet<>();
    private final Map<UUID, Match> byPlayer = new HashMap<>();
    /** External spectators (people who used /bedfight spec). */
    private final Map<UUID, Match> spectating = new HashMap<>();
    private final Map<String, Match> byWorld = new HashMap<>();

    public GameManager(BedFightPlugin plugin) {
        this.plugin = plugin;
        for (Mode m : Mode.values()) {
            queues.put(m, new LinkedHashSet<>());
        }
    }

    /** The match this player is PLAYING in. */
    public Match getMatch(Player p) { return byPlayer.get(p.getUniqueId()); }

    /** The match this player is spectating via /bedfight spec (not as a participant). */
    public Match getSpectatedMatch(Player p) { return spectating.get(p.getUniqueId()); }

    /** The match the player is in, as a participant OR as an external spectator. */
    public Match getMatchAny(Player p) {
        Match m = byPlayer.get(p.getUniqueId());
        return m != null ? m : spectating.get(p.getUniqueId());
    }

    public Match getMatchByWorld(World w) { return byWorld.get(w.getName()); }
    public Mode getQueuedMode(Player p) { return queued.get(p.getUniqueId()); }
    public int getQueueSize(Mode m) { return queues.get(m).size(); }
    public Set<Match> allMatches() { return matches; }

    public long getQueuedSince(Player p) {
        Long t = queuedAt.get(p.getUniqueId());
        return t == null ? System.currentTimeMillis() : t;
    }

    public Set<UUID> activePlayers() {
        Set<UUID> s = new HashSet<>(byPlayer.keySet());
        s.addAll(spectating.keySet());
        s.addAll(queued.keySet());
        return s;
    }

    public boolean isBusy(Player p) {
        UUID id = p.getUniqueId();
        return byPlayer.containsKey(id) || queued.containsKey(id) || spectating.containsKey(id);
    }

    // ---------------------------------------------------------------- queue

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
                plugin.msg(p, member.getName() + " is already in a queue, match or spectating.");
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
            plugin.msg(member, "Joined the " + mode.label() + " queue (" + q.size() + "/" + mode.totalPlayers()
                    + "). Use /bedfight leave to leave.");
            // switch the scoreboard right now instead of waiting for the next tick
            plugin.getScoreboards().refresh(member);
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
        plugin.getScoreboards().refresh(p);
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
                // somebody went offline: put the rest back and stop (avoids an endless loop)
                for (Player pl : players) {
                    q.add(pl.getUniqueId());
                    queued.put(pl.getUniqueId(), mode);
                    queuedAt.put(pl.getUniqueId(), System.currentTimeMillis());
                }
                return;
            }
            startMatch(arenas.get(random.nextInt(arenas.size())), mode, players, MatchOptions.defaults(plugin.getSettings()));
        }
    }

    // --------------------------------------------------------------- matches

    /** arena == null picks a random arena. */
    public String startPrivateMatch(Player host, Arena arena, Mode mode, MatchOptions options) {
        if (!plugin.getPartyHook().isAvailable()) {
            return "The party system (minestormparty) is not available.";
        }
        if (!plugin.getPartyHook().isLeader(host)) {
            return "Only the party leader can start a private match.";
        }
        List<Player> members = plugin.getPartyHook().getPartyMembers(host);
        if (members.size() != mode.totalPlayers()) {
            return "A " + mode.label() + " needs exactly " + mode.totalPlayers() + " players in your party (you have "
                    + members.size() + ").";
        }
        for (Player m : members) {
            if (isBusy(m)) {
                return m.getName() + " is already in a queue, match or spectating.";
            }
        }
        if (arena == null) {
            List<Arena> all = new ArrayList<>(plugin.getArenaManager().all());
            if (all.isEmpty()) {
                return "There are no arenas available.";
            }
            arena = all.get(random.nextInt(all.size()));
        }
        startMatch(arena, mode, members, options);
        return null;
    }

    public Match startMatch(Arena arena, Mode mode, List<Player> players, MatchOptions options) {
        for (Player p : players) {
            leaveQueue(p);
        }
        List<List<Player>> teams = plugin.getPartyHook().splitIntoTeams(players, mode.teamSize(), 2);
        Match match = new Match(plugin, this, arena, mode, options, teams);
        register(match);
        match.prepare();
        for (Player p : players) {
            plugin.getScoreboards().refresh(p);
        }
        return match;
    }

    private void register(Match m) {
        matches.add(m);
        byWorld.put(m.getWorldName(), m);
        for (UUID id : m.getPlayerIds()) {
            byPlayer.put(id, m);
        }
    }

    void detach(Player p) {
        byPlayer.remove(p.getUniqueId());
    }

    void unregister(Match m) {
        matches.remove(m);
        byWorld.remove(m.getWorldName());
        for (UUID id : m.getPlayerIds()) {
            byPlayer.remove(id, m);
        }
        for (UUID id : new ArrayList<>(spectating.keySet())) {
            if (spectating.get(id) == m) {
                spectating.remove(id);
            }
        }
    }

    // ------------------------------------------------------------- spectating

    /** /bedfight spec <target>. Returns an error message or null on success. */
    public String spectate(Player spectator, Player target) {
        if (!plugin.getSettings().getBoolean("spectator.enabled", true)) {
            return "Spectating is disabled.";
        }
        if (spectator.equals(target)) {
            return "You can't spectate yourself.";
        }
        UUID id = spectator.getUniqueId();
        if (byPlayer.containsKey(id) || queued.containsKey(id)) {
            return "Leave your queue or match first.";
        }
        Match m = getMatchAny(target);
        if (m == null) {
            return target.getName() + " is not in a BedFight match.";
        }
        if (m.getState() != Match.State.COUNTDOWN && m.getState() != Match.State.RUNNING) {
            return "That match is not running.";
        }
        if (!m.getOptions().allowSpectators) {
            return "That match does not allow spectators.";
        }
        Match old = spectating.get(id);
        if (old != null && old != m) {
            old.removeSpectator(spectator, false);
            spectating.remove(id);
        }
        spectating.put(id, m);
        m.addSpectator(spectator, target);
        plugin.getScoreboards().refresh(spectator);
        return null;
    }

    /** Leaves spectator mode and returns to the lobby. */
    public boolean stopSpectating(Player p) {
        Match m = spectating.remove(p.getUniqueId());
        if (m == null) {
            return false;
        }
        m.removeSpectator(p, true);
        plugin.getScoreboards().refresh(p);
        return true;
    }

    /** /leave: queue, match or spectator mode. Returns false when the player was in none of them. */
    public boolean leaveAny(Player p) {
        if (leaveQueue(p)) {
            plugin.msg(p, "You left the queue.");
            return true;
        }
        Match m = getMatch(p);
        if (m != null) {
            m.onLeave(p);
            plugin.msg(p, "You left the BedFight match.");
            return true;
        }
        if (stopSpectating(p)) {
            plugin.msg(p, "You stopped spectating.");
            return true;
        }
        return false;
    }

    /** Called when a player disconnects. */
    public void handleQuit(Player p) {
        leaveQueue(p);
        Match m = byPlayer.get(p.getUniqueId());
        if (m != null) {
            m.onQuit(p);
        }
        Match s = spectating.remove(p.getUniqueId());
        if (s != null) {
            s.removeSpectator(p, false);
        }
    }

    public void shutdown() {
        for (Match m : new ArrayList<>(matches)) {
            m.forceEnd();
        }
    }
}
