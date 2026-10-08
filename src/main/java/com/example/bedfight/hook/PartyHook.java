package com.example.bedfight.hook;

import com.example.bedfight.BedFightPlugin;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Talks to the optional "minestormparty" plugin through reflection (no compile dependency). */
public final class PartyHook {
    private static final String[] API_ACCESSORS = {"getPartyManager", "getPartyAPI", "getAPI", "getPartyService"};
    private static final String[] GET_PARTY = {"getParty", "getPartyOf", "getPlayerParty"};
    private static final String[] PARTY_MEMBERS = {"getMembers", "getPlayers", "getOnlineMembers"};
    private static final String[] PARTY_LEADER = {"getLeader", "getOwner"};
    private static final String[] SPLIT = {"splitIntoTeams", "splitTeams", "randomTeams", "createTeams"};

    private final BedFightPlugin plugin;
    private Object api;
    private boolean available;

    public PartyHook(BedFightPlugin plugin) {
        this.plugin = plugin;
        Plugin party = Bukkit.getPluginManager().getPlugin("minestormparty");
        if (party == null || !party.isEnabled()) {
            plugin.getLogger().warning("minestormparty not found - team modes (2v2/3v3) are limited.");
            return;
        }
        Object resolved = null;
        for (String accessor : API_ACCESSORS) {
            Object o = call(party, new String[] {accessor});
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

    /** The player's party (leader first) or just the player when not in a party. */
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

    private static Object call(Object target, String[] names, Object... args) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length
                        && accepts(m.getParameterTypes(), args)) {
                    try {
                        m.setAccessible(true);
                        return m.invoke(target, args);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // try the next candidate
                    }
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
