package com.example.bedfight.game;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.arena.Arena;
import com.example.bedfight.arena.Teams;
import com.example.bedfight.arena.TeamSpec;
import com.example.bedfight.arena.Pos;
import com.example.bedfight.util.Ping;
import com.example.bedfight.util.Sounds;
import com.example.bedfight.util.Titles;
import com.example.bedfight.util.WorldFiles;
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
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class Match {
    public enum State { PREPARING, COUNTDOWN, RUNNING, ENDING }

    public record TeamView(DyeColor color, boolean bedAlive, int alive, boolean mine) {}

    private record Damage(UUID by, long time) {}

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

    private static final AtomicInteger IDS = new AtomicInteger();

    private final BedFightPlugin plugin;
    private final GameManager games;
    private final Arena arena;
    private final Mode mode;
    private final MatchOptions options;
    private final String worldName;

    private final Map<DyeColor, TeamState> teams = new LinkedHashMap<>();
    private final Map<UUID, TeamState> byPlayer = new LinkedHashMap<>();
    private final Set<UUID> respawning = new HashSet<>();
    private final Set<UUID> spectators = new LinkedHashSet<>();
    private final Map<UUID, Damage> lastDamage = new HashMap<>();
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    /** Positions of blocks placed by players. ONLY these can be broken (see onBreak). */
    private final Set<Long> placed = new HashSet<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> bedBreaks = new HashMap<>();

    private State state = State.PREPARING;
    private boolean finished;
    private World world;
    private BukkitTask countdownTask;
    private BukkitTask ticker;
    private int countdownLeft;
    private long startMillis;

    public Match(BedFightPlugin plugin, GameManager games, Arena arena, Mode mode, MatchOptions options,
                 List<List<Player>> split) {
        this.plugin = plugin;
        this.games = games;
        this.arena = arena;
        this.mode = mode;
        this.options = options;
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

    // ------------------------------------------------------------ accessors

    public State getState() { return state; }
    public String getWorldName() { return worldName; }
    public World getWorld() { return world; }
    public Arena getArena() { return arena; }
    public Mode getMode() { return mode; }
    public MatchOptions getOptions() { return options; }
    public Set<UUID> getPlayerIds() { return byPlayer.keySet(); }

    public boolean isPlacedBlock(Block b) { return placed.contains(key(b)); }
    public void markPlaced(Block b) { placed.add(key(b)); }
    public boolean unmarkPlaced(Block b) { return placed.remove(key(b)); }

    /** Participants that are alive and fighting (not respawning / eliminated). */
    public List<Player> activePlayers() {
        List<Player> out = new ArrayList<>();
        for (UUID id : byPlayer.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && isActive(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Everybody who should see this match's messages: participants + spectators. */
    public List<Player> viewers() {
        List<Player> out = new ArrayList<>();
        for (UUID id : byPlayer.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                out.add(p);
            }
        }
        for (UUID id : spectators) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** True for dead/respawning participants and for people using /bedfight spec. */
    public boolean isGhost(Player p) {
        UUID id = p.getUniqueId();
        if (spectators.contains(id)) {
            return true;
        }
        TeamState t = byPlayer.get(id);
        return t != null && state == State.RUNNING && (!t.alive.contains(id) || respawning.contains(id));
    }

    private boolean isActive(Player p) {
        TeamState t = byPlayer.get(p.getUniqueId());
        return state == State.RUNNING && t != null && t.alive.contains(p.getUniqueId())
                && !respawning.contains(p.getUniqueId());
    }

    // ------------------------------------------------------------ lifecycle

    public void prepare() {
        broadcast(ChatColor.GRAY + "Preparing your " + ChatColor.WHITE + arena.getName() + ChatColor.GRAY + " arena...");
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
                Bukkit.getScheduler().runTask(plugin, () -> abort("Could not prepare the arena."));
            }
        });
    }

    private void begin() {
        Path dst = new File(Bukkit.getWorldContainer(), worldName).toPath();
        if (state != State.PREPARING) {
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
        world.setPVP(true);
        world.setDifficulty(Difficulty.NORMAL);
        world.setStorm(false);
        world.setTime(6000L);
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setGameRuleValue("doMobSpawning", "false");
        world.setGameRuleValue("mobGriefing", "false");
        Pos sp = arena.getSpawn();
        world.setSpawnLocation(sp.blockX() + 100000, 64, sp.blockZ() + 100000);

        for (TeamSpec spec : arena.getTeams().values()) {
            // unused teams never get a bed; with "beds disabled" nobody has one
            if (!teams.containsKey(spec.getColor()) || !options.bedsEnabled) {
                removeBed(world.getBlockAt(spec.getBed().blockX(), spec.getBed().blockY(), spec.getBed().blockZ()));
            }
        }
        if (!options.bedsEnabled) {
            for (TeamState t : teams.values()) {
                t.bedAlive = false;
            }
        }

        state = State.COUNTDOWN;
        for (TeamState t : teams.values()) {
            for (UUID id : t.members) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    equip(p, t);
                    p.teleport(t.spawn.toLocation(world));
                    p.sendMessage(BedFightPlugin.PREFIX + "You are on team " + Teams.display(t.color) + ChatColor.GRAY
                            + (options.bedsEnabled ? ". Protect your bed, destroy the enemy bed!" : ". Beds are disabled - there are no respawns!"));
                }
            }
        }
        for (Player p : viewers()) {
            plugin.getScoreboards().refresh(p);
        }

        final int seconds = Math.max(1, plugin.getSettings().getInt("game.countdown-seconds", 5));
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
                String soundKey = left <= 3 ? "countdown-last" : "countdown-tick";
                for (Player p : viewers()) {
                    Titles.send(p, "" + c + ChatColor.BOLD + left, ChatColor.GRAY + "Get ready to fight!", 0, 25, 5);
                    // ticking sound, once per second, together with the title
                    Sounds.play(plugin, p, soundKey);
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
                    Titles.send(p, "" + ChatColor.GREEN + ChatColor.BOLD + "FIGHT!",
                            ChatColor.GRAY + "Team " + Teams.display(t.color) + ChatColor.GRAY
                                    + (options.bedsEnabled ? " - destroy the enemy bed!" : " - last team standing wins!"),
                            0, 30, 10);
                    Sounds.play(plugin, p, "fight-start");
                }
            }
        }
        ticker = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != State.RUNNING) {
                    cancel();
                    return;
                }
                if (options.timeLimitMinutes > 0
                        && System.currentTimeMillis() - startMillis >= options.timeLimitMinutes * 60_000L) {
                    cancel();
                    broadcast(ChatColor.YELLOW + "The time limit was reached.");
                    end(null);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
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
            broadcast(Teams.display(winner.color) + ChatColor.GRAY + " team wins the match!");
        }
        for (TeamState t : teams.values()) {
            boolean won = t == winner;
            for (UUID id : t.members) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) {
                    continue;
                }
                if (winner == null) {
                    Titles.send(p, "" + ChatColor.YELLOW + ChatColor.BOLD + "DRAW", "", 0, 60, 10);
                    continue;
                }
                if (won) {
                    Titles.send(p, "" + ChatColor.GOLD + ChatColor.BOLD + "VICTORY!",
                            ChatColor.GRAY + "You won the BedFight!", 0, 80, 10);
                    Sounds.play(plugin, p, "victory");
                    continue;
                }
                Titles.send(p, "" + ChatColor.RED + ChatColor.BOLD + "DEFEAT",
                        ChatColor.GRAY + "Better luck next time!", 0, 80, 10);
            }
        }
        long delay = plugin.getSettings().getLong("game.end-delay-seconds", 8L) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> finish(true), delay);
    }

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
        if (ticker != null) {
            ticker.cancel();
        }
        List<Player> everyone = viewers();
        for (Player p : everyone) {
            if (world != null && p.getWorld().equals(world)) {
                resetPlayer(p);
                p.teleport(plugin.getLobby());
            }
        }
        games.unregister(this);
        for (Player p : everyone) {
            plugin.getScoreboards().refresh(p);
        }
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

    // --------------------------------------------------- leaving / spectating

    public void onQuit(Player p) {
        leave(p, p.getName() + " disconnected.");
    }

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
            resetPlayer(p);
            if (p.isOnline()) {
                p.teleport(plugin.getLobby());
            }
            eliminate(p, ChatColor.GRAY + message, false);
        } else if (state == State.ENDING && world != null && p.getWorld().equals(world)) {
            games.detach(p);
            resetPlayer(p);
            p.teleport(plugin.getLobby());
        }
        plugin.getScoreboards().refresh(p);
    }

    /** Puts a person (not playing in this match) into spectator mode next to target. */
    public void addSpectator(Player p, Player target) {
        spectators.add(p.getUniqueId());
        plugin.getSpectators().enable(p, this, true);
        Location to = target.getWorld().equals(world)
                ? target.getLocation().clone().add(0, 2, 0)
                : arena.getSpawn().toLocation(world);
        p.teleport(to);
        plugin.msg(p, ChatColor.GREEN + "You are now spectating " + ChatColor.WHITE + target.getName()
                + ChatColor.GREEN + ". Use /leave to stop.");
    }

    public void removeSpectator(Player p, boolean toLobby) {
        spectators.remove(p.getUniqueId());
        plugin.getSpectators().disable(p);
        if (toLobby && p.isOnline()) {
            resetPlayer(p);
            p.teleport(plugin.getLobby());
        }
    }

    // ------------------------------------------------- block protection

    /**
     * Players may only place blocks inside the arena box. Every placed block is remembered, because
     * those are the ONLY blocks that may be broken later.
     */
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (!isActive(p)) {
            e.setCancelled(true);
            return;
        }
        if (!arena.inBuildArea(b.getX(), b.getY(), b.getZ())) {
            e.setCancelled(true);
            p.sendMessage(plugin.getSettings().message("cannot-build", "&cYou cannot build here!"));
            return;
        }
        e.setCancelled(false);
        e.setBuild(true);
        placed.add(key(b));
    }

    /** Map blocks are NEVER breakable. Only the bed (via breakBed) and blocks placed by players are. */
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (!isActive(p)) {
            e.setCancelled(true);
            return;
        }
        if (b.getType() == Material.BED_BLOCK) {
            e.setCancelled(true);
            breakBed(p, b);
            return;
        }
        if (placed.remove(key(b))) {
            e.setCancelled(false);
        } else {
            e.setCancelled(true);
            p.sendMessage(plugin.getSettings().message("cannot-break", "&cYou can only break blocks placed by players!"));
        }
    }

    /** Explosions only remove player-placed blocks (never the map, never beds). */
    public void onExplode(EntityExplodeEvent e) {
        e.setYield(0.0F);
        e.blockList().removeIf(b -> !placed.contains(key(b)));
        for (Block b : e.blockList()) {
            placed.remove(key(b));
        }
    }

    // ----------------------------------------------------------- combat

    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        if (state != State.RUNNING || !isActive(p)) {
            e.setCancelled(true);
            return;
        }
        Long until = protectedUntil.get(p.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) {
            e.setCancelled(true);
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && !options.fallDamage) {
            e.setCancelled(true);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent be) {
            Player attacker = attacker(be.getDamager());
            if (attacker != null) {
                if (!isActive(attacker) || byPlayer.get(attacker.getUniqueId()) == byPlayer.get(p.getUniqueId())) {
                    e.setCancelled(true);
                    return;
                }
                lastDamage.put(p.getUniqueId(), new Damage(attacker.getUniqueId(), System.currentTimeMillis()));
            }
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID || p.getHealth() - e.getFinalDamage() <= 0.0D) {
            e.setCancelled(true);
            handleDeath(p);
            return;
        }
        e.setCancelled(false);
    }

    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location to = e.getTo();
        Location from = e.getFrom();

        if (state == State.COUNTDOWN) {
            if (byPlayer.containsKey(p.getUniqueId()) && !spectators.contains(p.getUniqueId())) {
                if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
                    Location fixed = from.clone();
                    fixed.setYaw(to.getYaw());
                    fixed.setPitch(to.getPitch());
                    e.setTo(fixed);
                }
            }
            if (!isGhost(p)) {
                return;
            }
        }

        // Spectators / dead players: keep them around the arena (they can't pass through blocks anyway)
        if (isGhost(p) || spectators.contains(p.getUniqueId())) {
            if (to.getY() < arena.getMinY() - 2) {
                e.setTo(arena.getSpawn().toLocation(world));
                return;
            }
            int pad = Math.max(0, plugin.getSettings().getInt("spectator.boundary-padding", 8));
            if (!arena.inHorizontalRange(to.getX(), to.getZ(), pad) || to.getY() > arena.getMaxY() + 40) {
                Location back = from.clone();
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                e.setTo(back);
            }
            return;
        }

        if (state != State.RUNNING || to.getY() >= arena.getMinY()) {
            return;
        }
        if (isActive(p)) {
            handleDeath(p);
        }
    }

    // ----------------------------------------------------------- beds

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
        broadcast(ChatColor.GRAY + "The bed of team " + Teams.display(owner.color) + ChatColor.GRAY + " was destroyed by "
                + Teams.chat(mine.color) + p.getName() + ChatColor.GRAY + "!");
        Sounds.play(plugin, p, "bed-break");
        for (UUID id : owner.members) {
            Player victim = Bukkit.getPlayer(id);
            if (victim != null) {
                Titles.send(victim, "" + ChatColor.RED + ChatColor.BOLD + "BED DESTROYED!",
                        ChatColor.GRAY + "You will no longer respawn!", 0, 50, 10);
                Sounds.play(plugin, victim, "bed-destroyed");
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

    // ------------------------------------------------- death / respawn

    private void handleDeath(Player p) {
        if (!isActive(p)) {
            return;
        }
        TeamState t = byPlayer.get(p.getUniqueId());
        Damage d = lastDamage.remove(p.getUniqueId());
        long creditMs = Math.max(1, plugin.getSettings().getInt("game.kill-credit-seconds", 10)) * 1000L;
        Player killer = null;
        if (d != null && System.currentTimeMillis() - d.time() < creditMs) {
            Player k = Bukkit.getPlayer(d.by());
            if (k != null && !k.equals(p) && byPlayer.containsKey(k.getUniqueId())) {
                killer = k;
            }
        }
        boolean finalKill = !t.bedAlive;

        String who = Teams.chat(t.color) + p.getName();
        String how;
        if (killer != null) {
            TeamState kt = byPlayer.get(killer.getUniqueId());
            how = ChatColor.GRAY + " was killed by " + Teams.chat(kt.color) + killer.getName() + ChatColor.GRAY + ".";
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
            Sounds.play(plugin, killer, finalKill ? "final-kill" : "kill");
        } else {
            how = ChatColor.GRAY + " died.";
        }
        Sounds.play(plugin, p, "death");
        broadcast(who + how + (finalKill ? " " + ChatColor.AQUA + ChatColor.BOLD + "FINAL KILL!" : ""));

        resetPlayer(p);
        if (t.bedAlive) {
            respawn(p, t);
        } else {
            Titles.send(p, "" + ChatColor.RED + ChatColor.BOLD + "ELIMINATED",
                    ChatColor.GRAY + "You can't respawn.", 0, 50, 10);
            eliminate(p, null, true);
        }
    }

    private void respawn(Player p, final TeamState t) {
        final UUID id = p.getUniqueId();
        respawning.add(id);
        // temporary spectator state (no items) while waiting
        plugin.getSpectators().enable(p, this, false);
        p.teleport(arena.getSpawn().toLocation(world));
        final int total = Math.max(1, options.respawnDelaySeconds);
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
                    plugin.getSpectators().disable(pl);
                    equip(pl, t);
                    pl.teleport(t.spawn.toLocation(world));
                    long prot = Math.max(0, plugin.getSettings().getInt("game.respawn-protection-seconds", 3)) * 1000L;
                    protectedUntil.put(id, System.currentTimeMillis() + prot);
                    Titles.send(pl, "" + ChatColor.GREEN + ChatColor.BOLD + "RESPAWNED", "", 0, 20, 10);
                    return;
                }
                Titles.send(pl, "" + ChatColor.RED + ChatColor.BOLD + "YOU DIED!",
                        ChatColor.YELLOW + "Respawning in " + ChatColor.RED + left + ChatColor.YELLOW + "s", 0, 25, 0);
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
            broadcast(message);
        }
        if (spectate && p.isOnline() && world != null) {
            plugin.getSpectators().enable(p, this, true);
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
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ utils

    private void broadcast(String message) {
        for (Player p : viewers()) {
            p.sendMessage(BedFightPlugin.PREFIX + message);
        }
    }

    private static long key(Block b) {
        return key(b.getX(), b.getY(), b.getZ());
    }

    /** Packs a block position into a long. (The old version shifted an int by 38 and collided.) */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private void resetPlayer(Player p) {
        plugin.getSpectators().disable(p);
        p.setGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        p.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20.0F);
        p.setFireTicks(0);
        p.setFallDistance(0.0F);
        for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(pe.getType());
        }
    }

    private void equip(Player p, TeamState t) {
        resetPlayer(p);
        plugin.getKitManager().apply(p, t.color);
        p.updateInventory();
    }

    // -------------------------------------------------- scoreboard data

    public List<TeamView> teamViews(Player viewer) {
        TeamState mine = byPlayer.get(viewer.getUniqueId());
        List<TeamView> out = new ArrayList<>();
        for (TeamState t : teams.values()) {
            out.add(new TeamView(t.color, t.bedAlive, t.alive.size(), t == mine));
        }
        return out;
    }

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
        long elapsed = (state == State.RUNNING || state == State.ENDING)
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
        m.put("%time%", String.format("%d:%02d", elapsed / 60L, elapsed % 60L));
        m.put("%kills%", String.valueOf(kills.getOrDefault(viewer.getUniqueId(), 0)));
        m.put("%bed_breaks%", String.valueOf(bedBreaks.getOrDefault(viewer.getUniqueId(), 0)));
        m.put("%spectators%", String.valueOf(spectators.size()));
        return m;
    }
}
