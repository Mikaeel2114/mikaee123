package com.example.bedfight.command;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.arena.Arena;
import com.example.bedfight.arena.ArenaManager;
import com.example.bedfight.arena.Pos;
import com.example.bedfight.arena.TeamSpec;
import com.example.bedfight.arena.Teams;
import com.example.bedfight.game.Mode;
import com.example.bedfight.gui.PrivateMatchGui;
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

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public final class BedFightCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "bedfight.admin";
    private static final List<String> ADMIN_SUBS = List.of("setuparena", "po1", "po2", "setspawn",
            "createteam", "setteam", "setbed", "buildmode", "save", "reload");

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

        // /leave and /l
        if (cmd.getName().equalsIgnoreCase("leave")) {
            return leave(p);
        }
        if (cmd.getName().equalsIgnoreCase("adminkiteditor")) {
            return kitEditor(p, args);
        }
        if (cmd.getName().equalsIgnoreCase("beddefend")) {
            return bedDefend(p, args);
        }

        // ---------------------------------------------------------- player side
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
        if (sub.equals("private")) {
            if (!p.hasPermission(PrivateMatchGui.PERMISSION)) {
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

        // ----------------------------------------------------------- admin side
        if (!ADMIN_SUBS.contains(sub)) {
            plugin.msg(p, ChatColor.RED + "Unknown sub-command. Usage: /bedfight [1v1|2v2|3v3|private|leave]");
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
                plugin.msg(p, ChatColor.GREEN + "World '" + args[1] + "' loaded. You are now setting up arena '"
                        + args[1] + "'. Next: /bedfight po1, po2, setspawn, createteam, setteam, setbed, save.");
            }
            return true;
        }

        if (sub.equals("buildmode")) {
            if (arenas.toggleBuildMode(p)) {
                p.sendMessage("you currently buildmode");
            } else {
                plugin.msg(p, "Build mode disabled. The map is protected again.");
            }
            return true;
        }

        if (sub.equals("reload")) {
            plugin.reloadConfig();
            arenas.loadAll();
            plugin.getKitManager().load();
            plugin.getScoreboards().reload();
            plugin.msg(p, ChatColor.GREEN + "Reloaded config.yml, scoreboard.yml, kit.yml and arenas.");
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
            case "save": {
                String err = arenas.save(p);
                plugin.msg(p, err == null
                        ? ChatColor.GREEN + "Arena '" + arena.getName() + "' saved to arenas/" + arena.getName() + ".yml"
                        : ChatColor.RED + err);
                break;
            }
            default:
                break;
        }
        return true;
    }

    // ------------------------------------------------------------- player cmds

    /** Leaves the queue, or forfeits the current match. */
    private boolean leave(Player p) {
        if (plugin.getGameManager().leaveQueue(p)) {
            plugin.msg(p, "You left the queue.");
            return true;
        }
        com.example.bedfight.game.Match m = plugin.getGameManager().getMatch(p);
        if (m != null) {
            m.onLeave(p);
            plugin.msg(p, "You left the BedFight match.");
            return true;
        }
        plugin.msg(p, ChatColor.RED + "You are not in a BedFight queue or match.");
        return true;
    }

    private boolean queue(Player p, Mode mode) {
        if (!p.hasPermission("bedfight.play")) {
            plugin.msg(p, ChatColor.RED + "You don't have permission to play BedFight.");
            return true;
        }
        plugin.getGameManager().joinQueue(p, mode == null ? Mode.SOLO : mode);
        return true;
    }

    // --------------------------------------------------------------- admin cmds

    /** /adminkiteditor [reset] */
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

    /**
     * /beddefend po1|po2|clear [color]
     * Marks the region around a team's bed whose map-built blocks may be broken during a match.
     */
    private boolean bedDefend(Player p, String[] args) {
        if (!p.hasPermission(ADMIN)) {
            plugin.msg(p, ChatColor.RED + "You don't have permission to do that.");
            return true;
        }
        Arena arena = plugin.getArenaManager().session(p);
        if (arena == null) {
            plugin.msg(p, ChatColor.RED + "Start with /bedfight setuparena <world_name> first.");
            return true;
        }
        String op = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!op.equals("po1") && !op.equals("po2") && !op.equals("clear")) {
            plugin.msg(p, ChatColor.RED + "Usage: /beddefend <po1|po2|clear> [color]");
            return true;
        }
        DyeColor c = resolveTeam(p, arena, args); // color is args[1], same shape as setteam/setbed
        if (c == null) {
            return true;
        }
        TeamSpec t = arena.team(c, true);
        if (op.equals("clear")) {
            t.setDefense1(null);
            t.setDefense2(null);
            plugin.msg(p, ChatColor.GREEN + "Bed defense region of team " + Teams.display(c) + ChatColor.GREEN + " cleared.");
            return true;
        }
        Pos pos = Pos.ofBlock(p.getLocation().getBlock());
        if (op.equals("po1")) {
            t.setDefense1(pos);
        } else {
            t.setDefense2(pos);
        }
        String msg = ChatColor.GREEN + "Bed defense " + op + " of team " + Teams.display(c) + ChatColor.GREEN
                + " set to " + pos.blockX() + ", " + pos.blockY() + ", " + pos.blockZ() + ".";
        if (t.hasDefense()) {
            int dx = Math.abs(t.getDefense1().blockX() - t.getDefense2().blockX()) + 1;
            int dy = Math.abs(t.getDefense1().blockY() - t.getDefense2().blockY()) + 1;
            int dz = Math.abs(t.getDefense1().blockZ() - t.getDefense2().blockZ()) + 1;
            msg += ChatColor.GRAY + " Region " + dx + "x" + dy + "x" + dz
                    + ": blocks inside can be broken during matches. Don't forget /bedfight save.";
        } else {
            msg += ChatColor.GRAY + " Now set the opposite corner with /beddefend " + (op.equals("po1") ? "po2" : "po1") + ".";
        }
        plugin.msg(p, msg);
        return true;
    }

    private void setBound(Player p, Arena arena, boolean first) {
        Pos pos = Pos.ofBlock(p.getLocation().getBlock());
        if (first) {
            arena.setPos1(pos);
        } else {
            arena.setPos2(pos);
        }
        String msg = ChatColor.GREEN + (first ? "po1" : "po2") + " set to " + pos.blockX() + ", "
                + pos.blockY() + ", " + pos.blockZ() + ".";
        if (arena.hasBounds()) {
            msg += ChatColor.GRAY + " Void level (min Y) = " + ChatColor.WHITE + arena.getMinY()
                    + ChatColor.GRAY + ", max build height (max Y) = " + ChatColor.WHITE + arena.getMaxY() + ".";
        }
        plugin.msg(p, msg);
        if (arena.hasBounds() && arena.getMaxY() - arena.getMinY() < 8) {
            plugin.msg(p, ChatColor.YELLOW + "Warning: only " + (arena.getMaxY() - arena.getMinY())
                    + " blocks between void level and build limit. Players can't build above Y "
                    + arena.getMaxY() + " - set one point at the lowest void level and one high above the islands.");
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
        plugin.msg(p, ChatColor.GREEN + "Team " + Teams.display(c) + ChatColor.GREEN
                + (existed ? " already exists, selected." : " registered and selected.")
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
        // players spawn centred on top of the registered block
        arena.team(c, true).setSpawn(new Pos(under.getX() + 0.5, under.getY() + 1, under.getZ() + 0.5,
                loc.getYaw(), 0f));
        plugin.msg(p, ChatColor.GREEN + "Spawn of team " + Teams.display(c) + ChatColor.GREEN + " set to block "
                + under.getX() + ", " + under.getY() + ", " + under.getZ() + ".");
    }

    private void setBed(Player p, Arena arena, String[] args) {
        DyeColor c = resolveTeam(p, arena, args);
        if (c == null) {
            return;
        }
        Block bed = p.getTargetBlock((java.util.HashSet<Byte>) null, 6);
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

    /** Team from the optional argument, else the last created/selected team. */
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

    // ----------------------------------------------------------- tab completion

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
        if (cn.equals("beddefend")) {
            if (args.length == 1) {
                out.addAll(List.of("po1", "po2", "clear"));
            } else if (args.length == 2) {
                Arena a = plugin.getArenaManager().session(p);
                if (a != null) {
                    for (DyeColor c : a.getTeams().keySet()) {
                        out.add(c.name().toLowerCase(Locale.ROOT));
                    }
                }
            }
            return filter(out, args);
        }
        if (args.length == 1) {
            out.addAll(List.of("1v1", "2v2", "3v3", "leave"));
            if (p.hasPermission(PrivateMatchGui.PERMISSION)) {
                out.add("private");
            }
            if (p.hasPermission(ADMIN)) {
                out.addAll(ADMIN_SUBS);
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
                HashSet<String> names = new HashSet<>();
                for (World w : Bukkit.getWorlds()) {
                    names.add(w.getName());
                }
                File[] dirs = Bukkit.getWorldContainer().listFiles(
                        f -> f.isDirectory() && new File(f, "level.dat").isFile());
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
