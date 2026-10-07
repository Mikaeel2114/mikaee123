package com.example.bedfight.game;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.arena.Arena;
import com.example.bedfight.arena.Pos;
import com.example.bedfight.arena.TeamSpec;
import com.example.bedfight.arena.Teams;
import com.example.bedfight.util.Ping;
import com.example.bedfight.util.Titles;
import com.example.bedfight.util.WorldFiles;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/** One running BedFight match on its own temporary copy of the arena world. */
public final class Match {

    public enum State { PREPARING, COUNTDOWN, RUNNING, ENDING }

    private static final AtomicInteger IDS = new AtomicInteger();

    private static final class TeamState {
        final DyeColor color;
        final Pos spawn;
        final Pos bed;
        final Set<UUID> members = new LinkedHashSet<>();
        final Set<UUID> alive = new HashSet<>();
        boolean bedAlive = true;

        TeamState(DyeColor color, Pos spawn, Pos bed) {
            this.color = color;
            this.spawn = spawn;
            this.bed = bed;
        }
    }

    private record Damage(UUID by, long time) {}

    private final BedFightPlugin plugin;
    private final GameManager games;
    private final Arena arena;
    private final Mode mode;
    private final String worldName;
    private final Map<DyeColor, TeamState> teams = new LinkedHashMap<>();
    private final Map<UUID, TeamState> byPlayer = new HashMap<>();
    private final Set<UUID> respawning = new HashSet<>();
    private final Map<UUID, Damage> lastDamage = new HashMap<>();
    private final Set<Long> placed = new HashSet<>();

    private State state = State.PREPARING;
    private boolean finished;
    private World world;
    private BukkitTask countdownTask;
    private int countdownLeft;
    private long startMillis;
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> bedBreaks = new HashMap<>();

    public Match(BedFightPlugin plugin, GameManager games, Arena arena, Mode mode, List<List<Player>> split) {
        this.plugin = plugin;
        this.games = games;
        this.arena = arena;
        this.mode = mode;
        this.worldName = "bf_" + arena.getWorldName() + "_" + IDS.incrementAndGet();

        List<DyeColor> colors = new ArrayList<>(arena.getTeams().keySet());
        Collections.shuffle(colors);
        for (int i = 0; i < split.size(); i++) {
            TeamSpec spec = arena.getTeams().get(colors.get(i));
            TeamState ts = new TeamState(spec.getColor(), spec.getSpawn(), spec.getBed());
            for (Player p : split.get(i)) {
                ts.members.add(p.getUniqueId());
                ts.alive.add(p.getUniqueId());
                byPlayer.put(p.getUniqueId(), ts);
            }
            teams.put(ts.color, ts);
        }
    }

    /** Row of the scoreboard team list. */
    public record TeamView(DyeColor color, boolean bedAlive, int alive, boolean mine) {}

    public State getState() { return state; }

    public String getWorldName() { return worldName; }
    public World getWorld() { return world; }
    public Set<UUID> getPlayerIds() { return byPlayer.keySet(); }

    // ------------------------------------------------------------- lifecycle

    /** Copies the arena world asynchronously, then starts the match on the main thread. */
    public void prepare() {
        broadcast(ChatColor.GRAY + "Preparing your " + mode.label() + " arena...");
        File src = new File(Bukkit.getWorldContainer(), arena.getWorldName());
        File dst = new File(Bukkit.getWorldContainer(), worldName);

        World template = Bukkit.getWorld(arena.getWorldName());
        if (template != null) {
            template.save();
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                WorldFiles.copy(src.toPath(), dst.toPath());
                Bukkit.getScheduler().runTask(plugin, this::begin);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "Could not copy arena world " + src, ex);
                Bukkit.getScheduler().runTask(plugin, () -> abort("Could not create the arena."));
            }
        });
    }

    private void begin() {
        Path dst = new File(Bukkit.getWorldContainer(), worldName).toPath();
        if (state != State.PREPARING) { // aborted while the copy was running
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> WorldFiles.delete(dst));
            return;
        }
        for (UUID id : byPlayer.keySet()) {
            if (Bukkit.getPlayer(id) == null) {
                abort("A player left before the match started.");
                return;
            }
        }
        world = Bukkit.createWorld(new WorldCreator(worldName));
        if (world == null) {
            abort("Could not load the arena.");
            return;
        }
        world.setAutoSave(false);
        world.setKeepSpawnInMemory(false);
        world.setPVP(true);                     // combat must be enabled in this world
        world.setDifficulty(Difficulty.NORMAL); // not peaceful
        world.setStorm(false);
        world.setTime(6000L);
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setGameRuleValue("doMobSpawning", "false");
        // Vanilla "spawn-protection" blocks non-ops from breaking/placing near the world spawn.
        // Move the world spawn far away so beds and bed defenses can be edited.
        Pos sp = arena.getSpawn();
        world.setSpawnLocation(sp.blockX() + 100_000, 64, sp.blockZ() + 100_000);

        // remove beds of arena teams that are not playing
        for (TeamSpec spec : arena.getTeams().values()) {
            if (!teams.containsKey(spec.getColor())) {
                removeBed(world.getBlockAt(spec.getBed().blockX(), spec.getBed().blockY(), spec.getBed().blockZ()));
            }
        }

        // frozen countdown with title screen
        state = State.COUNTDOWN;
        for (TeamState t : teams.values()) {
            for (UUID id : t.members) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    equip(p, t);
                    p.teleport(t.spawn.toLocation(world));
                    p.sendMessage(BedFightPlugin.PREFIX + ChatColor.GRAY + "You are on team " + Teams.display(t.color)
                            + ChatColor.GRAY + ". Protect your bed, destroy the enemy bed!");
                }
            }
        }
        final int seconds = Math.max(1, plugin.getConfig().getInt("countdown-seconds", 5));
        countdownTask = new BukkitRunnable() {
            int left = seconds;

            @Override
            public void run() {
                if (state != State.COUNTDOWN) {
                    cancel();
                    return;
                }
                if (left <= 0) {
                    cancel();
                    startGame();
                    return;
                }
                countdownLeft = left;
                ChatColor c = left > 3 ? ChatColor.GREEN : (left == 3 ? ChatColor.YELLOW : ChatColor.RED);
                for (UUID id : byPlayer.keySet()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        Titles.send(p, c + "" + ChatColor.BOLD + left, ChatColor.GRAY + "Get ready to fight!", 0, 25, 5);
                        p.playSound(p.getLocation(), Sound.NOTE_STICKS, 1f, 1f);
                    }
                }
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void startGame() {
        if (state != State.COUNTDOWN) {
            return;
        }
        state = State.RUNNING;
        startMillis = System.currentTimeMillis();
        for (TeamState t : teams.values()) {
            for (UUID id : t.members) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Titles.send(p, ChatColor.GREEN + "" + ChatColor.BOLD + "FIGHT!",
                            ChatColor.GRAY + "Team " + Teams.display(t.color) + ChatColor.GRAY
                                    + " - destroy the enemy bed!", 0, 30, 10);
                    p.playSound(p.getLocation(), Sound.NOTE_PLING, 1f, 2f);
                }
            }
        }
    }

    private void abort(String reason) {
        if (state == State.ENDING) {
            return;
        }
        state = State.ENDING;
        broadcast(ChatColor.RED + reason);
        finish(true);
    }

    private void end(TeamState winner) {
        if (state == State.ENDING) {
            return;
        }
        state = State.ENDING;
        if (winner == null) {
            broadcast(ChatColor.YELLOW + "The match ended in a draw.");
        } else {
            broadcast(Teams.display(winner.color) + ChatColor.GREEN + " team wins the match!");
        }
        for (TeamState t : teams.values()) {
            boolean won = t == winner;
            for (UUID id : t.members) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) {
                    continue;
                }
                if (winner == null) {
                    Titles.send(p, ChatColor.YELLOW + "" + ChatColor.BOLD + "DRAW", "", 0, 60, 10);
                } else if (won) {
                    Titles.send(p, ChatColor.GOLD + "" + ChatColor.BOLD + "VICTORY!",
                            ChatColor.GRAY + "You won the BedFight!", 0, 80, 10);
                    p.playSound(p.getLocation(), Sound.LEVEL_UP, 1f, 1f);
                } else {
                    Titles.send(p, ChatColor.RED + "" + ChatColor.BOLD + "DEFEAT",
                            ChatColor.GRAY + "Better luck next time!", 0, 80, 10);
                }
            }
        }
        long delay = plugin.getConfig().getLong("end-delay-seconds", 8L) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> finish(true), delay);
    }

    /** Immediate shutdown (server stop). */
    public void forceEnd() {
        state = State.ENDING;
        finish(false);
    }

    private void finish(boolean async) {
        if (finished) {
            return;
        }
        finished = true;
        if (countdownTask != null) {
            countdownTask.cancel();
        }
        for (UUID id : byPlayer.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && world != null && p.getWorld().equals(world)) {
                reset(p);
                p.teleport(plugin.getLobby());
            }
        }
        games.unregister(this);
        if (world != null) {
            Bukkit.unloadWorld(world, false);
            Path dir = new File(Bukkit.getWorldContainer(), worldName).toPath();
            if (async) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> WorldFiles.delete(dir));
            } else {
                WorldFiles.delete(dir);
            }
        }
    }

    // ---------------------------------------------------------------- events

    /** Player disconnected. */
    public void onQuit(Player p) {
        leave(p, p.getName() + " disconnected.");
    }

    /** Player used /leave (forfeits the match). */
    public void onLeave(Player p) {
        leave(p, p.getName() + " left the match.");
    }

    private void leave(Player p, String message) {
        if (state == State.PREPARING || state == State.COUNTDOWN) {
            abort(p.getName() + " left before the match started.");
            return;
        }
        if (state == State.RUNNING) {
            games.detach(p);
            reset(p);
            p.teleport(plugin.getLobby());
            eliminate(p, message, false);
        } else if (state == State.ENDING && world != null && p.getWorld().equals(world)) {
            games.detach(p);
            reset(p);
            p.teleport(plugin.getLobby());
        }
    }

    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (!isActive(p)) {
            e.setCancelled(true);
            return;
        }
        if (!arena.inBuildArea(b.getX(), b.getY(), b.getZ())) {
            e.setCancelled(true);
            plugin.msg(p, ChatColor.RED + "You can't build there! (Y limit: " + arena.getMinY() + " - "
                    + arena.getMaxY() + ")");
            return;
        }
        e.setCancelled(false);
        e.setBuild(true); // override vanilla spawn protection
        placed.add(key(b));
    }

    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (!isActive(p)) {
            e.setCancelled(true);
            return;
        }
        if (b.getType() == Material.BED_BLOCK) {
            e.setCancelled(true); // handled manually below
            breakBed(p, b);
            return;
        }
        // player-placed blocks and the map's bed-defense regions (/beddefend) can be broken
        if (placed.remove(key(b)) || arena.inAnyDefense(b.getX(), b.getY(), b.getZ())) {
            e.setCancelled(false);
        } else {
            e.setCancelled(true);
            plugin.msg(p, ChatColor.RED + "You can only break blocks placed by players or the bed defense!");
        }
    }

    public void onExplode(EntityExplodeEvent e) {
        e.setYield(0f);
        e.blockList().removeIf(b -> !placed.contains(key(b)) && !arena.inAnyDefense(b.getX(), b.getY(), b.getZ()));
        for (Block b : e.blockList()) {
            placed.remove(key(b));
        }
    }

    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        if (state != State.RUNNING || !isActive(p)) {
            e.setCancelled(true);
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            e.setCancelled(true); // fall damage is disabled
            return;
        }
        if (e instanceof EntityDamageByEntityEvent be) {
            Player attacker = attacker(be.getDamager());
            if (attacker != null) {
                if (!isActive(attacker) || byPlayer.get(attacker.getUniqueId()) == byPlayer.get(p.getUniqueId())) {
                    e.setCancelled(true); // spectators and friendly fire
                    return;
                }
                lastDamage.put(p.getUniqueId(), new Damage(attacker.getUniqueId(), System.currentTimeMillis()));
            }
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID || p.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            handleDeath(p);
            return;
        }
        e.setCancelled(false); // valid hit: make sure nothing else blocks it
    }

    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (state == State.COUNTDOWN) {
            if (byPlayer.containsKey(p.getUniqueId())) {
                Location from = e.getFrom();
                Location to = e.getTo();
                if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
                    Location fixed = from.clone(); // frozen, but allow looking around
                    fixed.setYaw(to.getYaw());
                    fixed.setPitch(to.getPitch());
                    e.setTo(fixed);
                }
            }
            return;
        }
        if (state != State.RUNNING || e.getTo().getY() >= arena.getMinY()) {
            return;
        }
        if (isActive(p)) {
            handleDeath(p); // fell below the void level (Min Y)
        }
    }

    // ----------------------------------------------------------------- logic

    private boolean isActive(Player p) {
        TeamState t = byPlayer.get(p.getUniqueId());
        return state == State.RUNNING && t != null
                && t.alive.contains(p.getUniqueId()) && !respawning.contains(p.getUniqueId());
    }

    private void breakBed(Player p, Block b) {
        TeamState owner = bedOwner(b);
        if (owner == null) {
            return;
        }
        TeamState mine = byPlayer.get(p.getUniqueId());
        if (owner == mine) {
            plugin.msg(p, ChatColor.RED + "You can't break your own bed!");
            return;
        }
        if (!owner.bedAlive) {
            return;
        }
        owner.bedAlive = false;
        bedBreaks.merge(p.getUniqueId(), 1, Integer::sum);
        removeBed(b);
        broadcast(ChatColor.GRAY + "The bed of team " + Teams.display(owner.color) + ChatColor.GRAY
                + " was destroyed by " + Teams.chat(mine.color) + p.getName() + ChatColor.GRAY + "!");
        for (UUID id : owner.members) {
            Player victim = Bukkit.getPlayer(id);
            if (victim != null) {
                Titles.send(victim, ChatColor.RED + "" + ChatColor.BOLD + "BED DESTROYED!",
                        ChatColor.GRAY + "You will no longer respawn!", 0, 50, 10);
                victim.playSound(victim.getLocation(), Sound.ENDERDRAGON_GROWL, 1f, 1f);
            }
        }
    }

    private TeamState bedOwner(Block b) {
        for (TeamState t : teams.values()) {
            int dx = Math.abs(b.getX() - t.bed.blockX());
            int dz = Math.abs(b.getZ() - t.bed.blockZ());
            if (b.getY() == t.bed.blockY() && dx + dz <= 1) {
                return t;
            }
        }
        return null;
    }

    /** Removes both halves of a bed. */
    private static void removeBed(Block b) {
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace f : faces) {
            Block n = b.getRelative(f);
            if (n.getType() == Material.BED_BLOCK) {
                n.setType(Material.AIR);
            }
        }
        b.setType(Material.AIR);
    }

    private void handleDeath(Player p) {
        if (!isActive(p)) {
            return;
        }
        TeamState t = byPlayer.get(p.getUniqueId());
        Damage d = lastDamage.remove(p.getUniqueId());
        Player killer = (d != null && System.currentTimeMillis() - d.time() < 10_000L) ? Bukkit.getPlayer(d.by()) : null;
        if (killer != null) {
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
        }
        String who = Teams.chat(t.color) + p.getName() + ChatColor.GRAY;
        String how = killer != null ? " was killed by " + killer.getName() : " died";

        reset(p);
        if (t.bedAlive) {
            broadcast(who + how + ".");
            respawn(p, t);
        } else {
            broadcast(who + how + ". " + ChatColor.RED + "ELIMINATED!");
            Titles.send(p, ChatColor.RED + "" + ChatColor.BOLD + "ELIMINATED", ChatColor.GRAY + "You can't respawn.",
                    0, 50, 10);
            eliminate(p, null, true);
        }
    }

    private void respawn(Player p, TeamState t) {
        UUID id = p.getUniqueId();
        respawning.add(id);
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(arena.getSpawn().toLocation(world));
        final int total = Math.max(1, plugin.getConfig().getInt("respawn-delay-seconds", 5));
        new BukkitRunnable() {
            int left = total;

            @Override
            public void run() {
                Player pl = Bukkit.getPlayer(id);
                if (state != State.RUNNING || !respawning.contains(id) || pl == null || !t.alive.contains(id)) {
                    respawning.remove(id);
                    cancel();
                    return;
                }
                if (left <= 0) {
                    cancel();
                    respawning.remove(id);
                    equip(pl, t);
                    pl.teleport(t.spawn.toLocation(world));
                    Titles.send(pl, ChatColor.GREEN + "" + ChatColor.BOLD + "RESPAWNED", "", 0, 20, 10);
                    return;
                }
                Titles.send(pl, ChatColor.RED + "" + ChatColor.BOLD + "YOU DIED!",
                        ChatColor.YELLOW + "Respawning in " + left + "s", 0, 25, 0);
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void eliminate(Player p, String message, boolean spectate) {
        TeamState t = byPlayer.get(p.getUniqueId());
        if (t == null || !t.alive.remove(p.getUniqueId())) {
            return;
        }
        respawning.remove(p.getUniqueId());
        if (message != null) {
            broadcast(ChatColor.GRAY + message);
        }
        if (spectate && p.isOnline() && world != null) {
            p.setGameMode(GameMode.SPECTATOR);
            p.teleport(arena.getSpawn().toLocation(world));
        }
        checkWin();
    }

    private void checkWin() {
        if (state != State.RUNNING) {
            return;
        }
        List<TeamState> left = new ArrayList<>();
        for (TeamState t : teams.values()) {
            if (!t.alive.isEmpty()) {
                left.add(t);
            }
        }
        if (left.size() <= 1) {
            end(left.isEmpty() ? null : left.get(0));
        }
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    // --------------------------------------------------------------- helpers

    private void broadcast(String message) {
        for (UUID id : byPlayer.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(BedFightPlugin.PREFIX + message);
            }
        }
    }

    private static long key(Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12) | (b.getY() & 0xFFFL);
    }

    private static void reset(Player p) {
        p.setGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(pe.getType());
        }
    }

    /** Gives the kit configured with /adminkiteditor (kit.yml), tinted to the team color. */
    private void equip(Player p, TeamState t) {
        reset(p);
        plugin.getKitManager().apply(p, t.color);
        p.updateInventory();
    }

    // ------------------------------------------------------------ scoreboard

    /** Scoreboard rows for every team (viewer's own team flagged). */
    public List<TeamView> teamViews(Player viewer) {
        TeamState mine = byPlayer.get(viewer.getUniqueId());
        List<TeamView> out = new ArrayList<>();
        for (TeamState t : teams.values()) {
            out.add(new TeamView(t.color, t.bedAlive, t.alive.size(), t == mine));
        }
        return out;
    }

    /** Placeholder values for scoreboard.yml (match section). */
    public Map<String, String> placeholders(Player viewer) {
        Map<String, String> m = new HashMap<>();
        TeamState mine = byPlayer.get(viewer.getUniqueId());
        List<String> enemyNames = new ArrayList<>();
        int pingSum = 0;
        int pingCount = 0;
        for (TeamState t : teams.values()) {
            if (t == mine) {
                continue;
            }
            for (UUID id : t.members) {
                Player pl = Bukkit.getPlayer(id);
                enemyNames.add(pl != null ? pl.getName() : "?");
                if (pl != null) {
                    pingSum += Ping.get(pl);
                    pingCount++;
                }
            }
        }
        long elapsed = state == State.RUNNING || state == State.ENDING
                ? Math.max(0L, (System.currentTimeMillis() - startMillis) / 1000L) : 0L;
        String status;
        switch (state) {
            case PREPARING: status = "Preparing"; break;
            case COUNTDOWN: status = "Starting in " + countdownLeft + "s"; break;
            case RUNNING: status = "Fighting"; break;
            default: status = "Ending"; break;
        }
        m.put("%player%", viewer.getName());
        m.put("%mode%", mode.label());
        m.put("%map%", arena.getName());
        m.put("%team%", mine != null ? Teams.display(mine.color) : "-");
        m.put("%opponent%", String.join(", ", enemyNames));
        m.put("%ping%", String.valueOf(Ping.get(viewer)));
        m.put("%opponent_ping%", String.valueOf(pingCount == 0 ? 0 : pingSum / pingCount));
        m.put("%status%", status);
        m.put("%countdown%", String.valueOf(countdownLeft));
        m.put("%time%", String.format("%d:%02d", elapsed / 60, elapsed % 60));
        m.put("%kills%", String.valueOf(kills.getOrDefault(viewer.getUniqueId(), 0)));
        m.put("%bed_breaks%", String.valueOf(bedBreaks.getOrDefault(viewer.getUniqueId(), 0)));
        return m;
    }
}
