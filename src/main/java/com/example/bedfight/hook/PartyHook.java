package com.example.bedfight.hook;

import com.example.bedfight.BedFightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hook into the custom "minestormparty" plugin.
 *
 * The real API of minestormparty is not known at compile time, so it is accessed through
 * reflection. If your plugin uses different method names, ONLY edit the name tables below.
 * Every lookup is tolerant: when something can't be resolved the hook falls back to
 * "no party" (single player) and a local random split.
 */
public final class PartyHook {

    private static final String PLUGIN_NAME = "minestormparty";

    /** Methods on the plugin's main class that return the API / party manager (tried in order). */
    private static final String[] API_ACCESSORS = {"getPartyManager", "getPartyAPI", "getAPI", "getPartyService"};
    /** api.getParty(Player) -> party object (or null when not in a party). */
    private static final String[] GET_PARTY = {"getParty", "getPartyOf", "getPlayerParty"};
    /** party.getMembers() -> Collection of Player / UUID. */
    private static final String[] PARTY_MEMBERS = {"getMembers", "getPlayers", "getOnlineMembers"};
    /** party.getLeader() -> Player / UUID. */
    private static final String[] PARTY_LEADER = {"getLeader", "getOwner"};
    /** api.splitIntoTeams(Collection players, int teamSize) -> Collection of Collections. */
    private static final String[] SPLIT = {"splitIntoTeams", "splitTeams", "randomTeams", "createTeams"};

    private final BedFightPlugin plugin;
    private Object api;
    private boolean available;

    public PartyHook(BedFightPlugin plugin) {
        this.plugin = plugin;
        Plugin party = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (party == null || !party.isEnabled()) {
            plugin.getLogger().warning("minestormparty not found - team modes (2v2/3v3) are limited.");
            return;
        }
        Object resolved = null;
        for (String accessor : API_ACCESSORS) {
            Object o = call(party, new String[]{accessor});
            if (o != null) {
                resolved = o;
                break;
            }
        }
        this.api = resolved != null ? resolved : party;
        this.available = true;
        plugin.getLogger().info("Hooked into minestormparty (" + api.getClass().getName() + ").");
    }

    public boolean isAvailable() { return available; }

    /** All members of the player's party (always contains the player itself). */
    public List<Player> getPartyMembers(Player player) {
        List<Player> out = new ArrayList<>();
        if (available) {
            Object party = call(api, GET_PARTY, player);
            if (party != null) {
                out.addAll(toPlayers(call(party, PARTY_MEMBERS)));
            }
        }
        out.remove(player);
        out.add(0, player);
        return out;
    }

    /** True when the player leads their party (or has no party / leader can't be determined). */
    public boolean isLeader(Player player) {
        if (!available) {
            return true;
        }
        Object party = call(api, GET_PARTY, player);
        if (party == null) {
            return true;
        }
        Object leader = call(party, PARTY_LEADER);
        if (leader instanceof Player p) {
            return p.getUniqueId().equals(player.getUniqueId());
        }
        if (leader instanceof UUID id) {
            return id.equals(player.getUniqueId());
        }
        return true;
    }

    /**
     * Randomly splits players into {@code teamCount} teams of {@code teamSize}.
     * Uses minestormparty's splitter when available and valid, else a local shuffle.
     */
    public List<List<Player>> splitIntoTeams(List<Player> players, int teamSize, int teamCount) {
        if (available) {
            Object raw = call(api, SPLIT, new ArrayList<>(players), teamSize);
            List<List<Player>> teams = toTeams(raw);
            if (isValidSplit(teams, players, teamSize, teamCount)) {
                return teams;
            }
        }
        List<Player> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        List<List<Player>> result = new ArrayList<>();
        for (int i = 0; i < teamCount; i++) {
            int from = i * teamSize;
            int to = Math.min(from + teamSize, shuffled.size());
            result.add(new ArrayList<>(shuffled.subList(from, to)));
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isValidSplit(List<List<Player>> teams, List<Player> players, int size, int count) {
        if (teams.size() != count) {
            return false;
        }
        Set<UUID> seen = new HashSet<>();
        for (List<Player> t : teams) {
            if (t.size() != size) {
                return false;
            }
            for (Player p : t) {
                seen.add(p.getUniqueId());
            }
        }
        for (Player p : players) {
            if (!seen.contains(p.getUniqueId())) {
                return false;
            }
        }
        return true;
    }

    private static List<Player> toPlayers(Object raw) {
        List<Player> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) {
            for (Object o : c) {
                Player p = null;
                if (o instanceof Player pl) {
                    p = pl;
                } else if (o instanceof UUID id) {
                    p = Bukkit.getPlayer(id);
                } else if (o instanceof String name) {
                    p = Bukkit.getPlayerExact(name);
                }
                if (p != null && p.isOnline() && !out.contains(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    private static List<List<Player>> toTeams(Object raw) {
        List<List<Player>> teams = new ArrayList<>();
        Collection<?> outer = null;
        if (raw instanceof Map<?, ?> m) {
            outer = m.values();
        } else if (raw instanceof Collection<?> c) {
            outer = c;
        }
        if (outer != null) {
            for (Object o : outer) {
                teams.add(toPlayers(o));
            }
        }
        return teams;
    }

    /** Finds a public method by name + compatible arguments and invokes it. Returns null on failure. */
    private static Object call(Object target, String[] names, Object... args) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) {
                    continue;
                }
                if (!accepts(m.getParameterTypes(), args)) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // try next candidate
                }
            }
        }
        return null;
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == int.class) t = Integer.class;
            if (t == long.class) t = Long.class;
            if (t == boolean.class) t = Boolean.class;
            if (args[i] == null || !t.isAssignableFrom(args[i].getClass())) {
                return false;
            }
        }
        return true;
    }
}
