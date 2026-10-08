package com.example.bedfight.command;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.arena.Arena;
import com.example.bedfight.arena.ArenaManager;
import com.example.bedfight.arena.Pos;
import com.example.bedfight.arena.Teams;
import com.example.bedfight.game.GameManager;
import com.example.bedfight.game.Match;
import com.example.bedfight.game.Mode;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class BedFightCommand implements CommandExecutor, TabCompleter {
    private static final String ADMIN = "bedfight.admin";
    private static final List<String> ADMIN_SUBS = List.of(
            "setuparena", "po1", "po2", "setspawn", "createteam", "setteam", "setbed", "buildmode", "save", "reload");

    private final BedFightPlugin plugin;

    public BedFightCommand(BedFightPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        ArenaManager arenas = plugin.getArenaManager();

        if (cmd.getName().equalsIgnoreCase("leave")) {
            return leave(p);
        }
        if (cmd.getName().equalsIgnoreCase("adminkiteditor")) {
            return kitEditor(p, args);
        }

        if (args.length == 0) {
            return queue(p, Mode.parse(plugin.getConfig().getString("default-mode", "1v1")));
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        Mode asMode = Mode.parse(sub);
        if (asMode != null) {
            return queue(p, asMode);
        }
        if (sub.equals("leave")) {
            return leave(p);
        }
        if (sub.equals("spec") || sub.equals("spectate")) {
            return spectate(p, args);
        }
        if (sub.equals("private")) {
            if (!p.hasPermission("bedfight.rank.mvpplusplus")) {
                plugin.msg(p, ChatColor.RED + "Only MVP++ players can create private matches.");
                return true;
            }
            if (plugin.getGameManager().isBusy(p)) {
                plugin.msg(p, ChatColor.RED + "You are already in a queue or match.");
                return true;
            }
            plugin.getPrivateGui().open(p);
            return true;
        }
        if (!ADMIN_SUBS.contains(sub)) {
            plugin.msg(p, ChatColor.RED + "Unknown sub-command. Usage: /bedfight [1v1|2v2|3v3|private|spec <player>|leave]");
            return true;
        }
        if (!p.hasPermission(ADMIN)) {
            plugin.msg(p, ChatColor.RED + "You don't have permission to do that.");
            return true;
        }

        if (sub.equals("setuparena")) {
            if (args.length < 2) {
                plugin.msg(p, ChatColor.RED + "Usage: /bedfight setuparena <world_name>");
                return true;
            }
            String err = arenas.setupArena(p, args[1]);
            if (err != null) {
                plugin.msg(p, ChatColor.RED + err);
            } else {
                plugin.msg(p, ChatColor.GREEN + "World '" + args[1] + "' loaded. You are now setting up arena '" + args[1]
                        + "'. Next: /bedfight po1, po2, setspawn, createteam, setteam, setbed, save.");
            }
            return true;
        }
        if (sub.equals("buildmode")) {
            if (arenas.toggleBuildMode(p)) {
                plugin.msg(p, ChatColor.GREEN + "Build mode enabled. You can edit protected arena worlds. Run again to disable it.");
            } else {
                plugin.msg(p, "Build mode disabled. The map is protected again.");
            }
            return true;
        }
        if (sub.equals("reload")) {
            plugin.reloadAll();
            plugin.msg(p, ChatColor.GREEN + "Reloaded config.yml, settings.yml, scoreboard.yml, kit.yml and arenas.");
            return true;
        }

        Arena arena = arenas.session(p);
        if (arena == null) {
            plugin.msg(p, ChatColor.RED + "Start with /bedfight setuparena <world_name> first.");
            return true;
        }
        switch (sub) {
            case "po1":
            case "po2":
                setBound(p, arena, sub.equals("po1"));
                break;
            case "setspawn":
                arena.setSpawn(Pos.of(p.getLocation()));
                plugin.msg(p, ChatColor.GREEN + "Game spawn set.");
                break;
            case "createteam":
                createTeam(p, arena, args);
                break;
            case "setteam":
                setTeamSpawn(p, arena, args);
                break;
            case "setbed":
                setBed(p, arena, args);
                break;
            case "save":
                String err = arenas.save(p);
                plugin.msg(p, err == null
                        ? ChatColor.GREEN + "Arena '" + arena.getName() + "' saved to arenas/" + arena.getName() + ".yml"
                        : ChatColor.RED + err);
                break;
            default:
                break;
        }
        return true;
    }

    // ------------------------------------------------------------- player commands

    private boolean leave(Player p) {
        if (!plugin.getGameManager().leaveAny(p)) {
            plugin.msg(p, ChatColor.RED + "You are not in a BedFight queue, match or spectating.");
        }
        return true;
    }

    private boolean queue(Player p, Mode mode) {
        if (!p.hasPermission("bedfight.play")) {
            plugin.msg(p, ChatColor.RED + "You don't have permission to play BedFight.");
            return true;
        }
        plugin.getGameManager().joinQueue(p, mode == null ? Mode.SOLO : mode);
        // switch to the queue scoreboard immediately (also covers the "already queued" case)
        plugin.getScoreboards().refresh(p);
        return true;
    }

    /** /bedfight spec <player> */
    private boolean spectate(Player p, String[] args) {
        if (!p.hasPermission("bedfight.spectate")) {
            plugin.msg(p, ChatColor.RED + "You don't have permission to spectate.");
            return true;
        }
        if (args.length < 2) {
            plugin.msg(p, ChatColor.RED + "Usage: /bedfight spec <player>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.msg(p, ChatColor.RED + "Player '" + args[1] + "' is not online.");
            return true;
        }
        String err = plugin.getGameManager().spectate(p, target);
        if (err != null) {
            plugin.msg(p, ChatColor.RED + err);
        }
        return true;
    }

    private boolean kitEditor(Player p, String[] args) {
        if (!p.hasPermission(ADMIN)) {
            plugin.msg(p, ChatColor.RED + "You don't have permission to do that.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            plugin.getKitManager().resetToDefault();
            plugin.msg(p, ChatColor.GREEN + "Kit reset to the default (iron sword, 64 wool, shears, golden apple, leather armor).");
            return true;
        }
        plugin.getKitEditor().open(p);
        return true;
    }

    // ------------------------------------------------------------- arena setup

    private void setBound(Player p, Arena arena, boolean first) {
        Pos pos = Pos.ofBlock(p.getLocation().getBlock());
        if (first) {
            arena.setPos1(pos);
        } else {
            arena.setPos2(pos);
        }
        String msg = ChatColor.GREEN + (first ? "po1" : "po2") + " set to " + pos.blockX() + ", " + pos.blockY() + ", " + pos.blockZ() + ".";
        if (arena.hasBounds()) {
            msg += " Void level (min Y) = " + ChatColor.WHITE + arena.getMinY() + ChatColor.GREEN
                    + ", max build height (max Y) = " + ChatColor.WHITE + arena.getMaxY() + ChatColor.GREEN + ".";
        }
        plugin.msg(p, msg);
        if (arena.hasBounds() && arena.getMaxY() - arena.getMinY() < 8) {
            plugin.msg(p, ChatColor.YELLOW + "Warning: only " + (arena.getMaxY() - arena.getMinY())
                    + " blocks between void level and build limit. Set one point at the lowest void level and one high above the islands.");
        }
    }

    private void createTeam(Player p, Arena arena, String[] args) {
        if (args.length < 2) {
            plugin.msg(p, ChatColor.RED + "Usage: /bedfight createteam <color>");
            return;
        }
        DyeColor c = Teams.parse(args[1]);
        if (c == null) {
            plugin.msg(p, ChatColor.RED + "Unknown color. Valid: " + colorList());
            return;
        }
        boolean existed = arena.getTeams().containsKey(c);
        arena.team(c, true);
        plugin.getArenaManager().select(p, c);
        plugin.msg(p, ChatColor.GREEN + "Team " + Teams.display(c) + ChatColor.GREEN + (existed ? " selected." : " created.")
                + " Now use /bedfight setteam and /bedfight setbed.");
    }

    private void setTeamSpawn(Player p, Arena arena, String[] args) {
        DyeColor c = resolveTeam(p, arena, args);
        if (c == null) {
            return;
        }
        Location loc = p.getLocation();
        Block under = loc.getBlock().getRelative(0, -1, 0);
        if (under.getType() == Material.AIR) {
            plugin.msg(p, ChatColor.RED + "Stand on a solid block - the block under your feet is the spawn.");
            return;
        }
        arena.team(c, true).setSpawn(new Pos(under.getX() + 0.5D, under.getY() + 1, under.getZ() + 0.5D, loc.getYaw(), 0.0F));
        plugin.msg(p, ChatColor.GREEN + "Spawn of team " + Teams.display(c) + ChatColor.GREEN + " set to block "
                + under.getX() + ", " + under.getY() + ", " + under.getZ() + ".");
    }

    private void setBed(Player p, Arena arena, String[] args) {
        DyeColor c = resolveTeam(p, arena, args);
        if (c == null) {
            return;
        }
        Block bed = p.getTargetBlock((HashSet<Byte>) null, 6);
        if (bed == null || bed.getType() != Material.BED_BLOCK) {
            Block feet = p.getLocation().getBlock();
            bed = feet.getType() == Material.BED_BLOCK ? feet : null;
        }
        if (bed == null) {
            plugin.msg(p, ChatColor.RED + "Look at the team's bed (within 6 blocks) and run the command again.");
            return;
        }
        arena.team(c, true).setBed(Pos.ofBlock(bed));
        plugin.msg(p, ChatColor.GREEN + "Bed of team " + Teams.display(c) + ChatColor.GREEN + " registered at "
                + bed.getX() + ", " + bed.getY() + ", " + bed.getZ() + ".");
    }

    private DyeColor resolveTeam(Player p, Arena arena, String[] args) {
        if (args.length >= 2) {
            DyeColor c = Teams.parse(args[1]);
            if (c == null || !arena.getTeams().containsKey(c)) {
                plugin.msg(p, ChatColor.RED + "That team doesn't exist. Use /bedfight createteam <color> first.");
                return null;
            }
            plugin.getArenaManager().select(p, c);
            return c;
        }
        DyeColor c = plugin.getArenaManager().selected(p);
        if (c == null || !arena.getTeams().containsKey(c)) {
            plugin.msg(p, ChatColor.RED + "No team selected. Use /bedfight createteam <color> or pass the color.");
            return null;
        }
        return c;
    }

    private static String colorList() {
        List<String> l = new ArrayList<>();
        for (DyeColor c : DyeColor.values()) {
            l.add(c.name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", l);
    }

    // ---------------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player p)) {
            return out;
        }
        String cn = cmd.getName().toLowerCase(Locale.ROOT);
        if (cn.equals("leave")) {
            return out;
        }
        if (cn.equals("adminkiteditor")) {
            if (args.length == 1) {
                out.add("reset");
            }
            return filter(out, args);
        }
        if (args.length == 1) {
            out.addAll(List.of("1v1", "2v2", "3v3", "spec", "leave"));
            if (p.hasPermission("bedfight.rank.mvpplusplus")) {
                out.add("private");
            }
            if (p.hasPermission(ADMIN)) {
                out.addAll(ADMIN_SUBS);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("spec") || args[0].equalsIgnoreCase("spectate"))) {
            GameManager games = plugin.getGameManager();
            for (Player online : Bukkit.getOnlinePlayers()) {
                Match m = games.getMatchAny(online);
                if (m != null && !online.equals(p)) {
                    out.add(online.getName());
                }
            }
        } else if (args.length == 2 && p.hasPermission(ADMIN)) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("createteam")) {
                for (DyeColor c : DyeColor.values()) {
                    out.add(c.name().toLowerCase(Locale.ROOT));
                }
            } else if (sub.equals("setteam") || sub.equals("setbed")) {
                Arena a = plugin.getArenaManager().session(p);
                if (a != null) {
                    for (DyeColor c : a.getTeams().keySet()) {
                        out.add(c.name().toLowerCase(Locale.ROOT));
                    }
                }
            } else if (sub.equals("setuparena")) {
                Set<String> names = new HashSet<>();
                for (World w : Bukkit.getWorlds()) {
                    names.add(w.getName());
                }
                File[] dirs = Bukkit.getWorldContainer().listFiles(f -> f.isDirectory() && new File(f, "level.dat").isFile());
                if (dirs != null) {
                    for (File f : dirs) {
                        names.add(f.getName());
                    }
                }
                out.addAll(names);
            }
        }
        return filter(out, args);
    }

    private static List<String> filter(List<String> out, String[] args) {
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
