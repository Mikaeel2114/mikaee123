package com.example.bedfight;

import com.example.bedfight.arena.ArenaManager;
import com.example.bedfight.command.BedFightCommand;
import com.example.bedfight.game.GameManager;
import com.example.bedfight.gui.PrivateMatchGui;
import com.example.bedfight.hook.PartyHook;
import com.example.bedfight.kit.KitEditorGui;
import com.example.bedfight.kit.KitManager;
import com.example.bedfight.listener.ArenaListener;
import com.example.bedfight.listener.SpectatorListener;
import com.example.bedfight.scoreboard.ScoreboardManager;
import com.example.bedfight.spectate.SpectatorManager;
import com.example.bedfight.util.ConfigMerger;
import com.example.bedfight.util.Settings;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public final class BedFightPlugin extends JavaPlugin {
    public static final String PREFIX = ChatColor.RED + "BedFight " + ChatColor.BOLD + "\u00BB " + ChatColor.RESET + ChatColor.GRAY;

    private Settings settings;
    private PartyHook partyHook;
    private ArenaManager arenaManager;
    private GameManager gameManager;
    private PrivateMatchGui privateGui;
    private KitManager kitManager;
    private KitEditorGui kitEditor;
    private SpectatorManager spectators;
    private ScoreboardManager scoreboards;

    @Override
    public void onEnable() {
        // create the files on first run, and add any keys that were introduced by this version
        getDataFolder().mkdirs();
        saveDefaultConfig();
        ConfigMerger.merge(this, "config.yml");
        reloadConfig();
        this.settings = new Settings(this);

        this.partyHook = new PartyHook(this);
        this.arenaManager = new ArenaManager(this);
        this.arenaManager.loadAll();
        this.gameManager = new GameManager(this);
        this.spectators = new SpectatorManager(this);
        this.privateGui = new PrivateMatchGui(this);
        this.kitManager = new KitManager(this);
        this.kitEditor = new KitEditorGui(this);
        this.scoreboards = new ScoreboardManager(this);

        getServer().getPluginManager().registerEvents(new ArenaListener(this), this);
        getServer().getPluginManager().registerEvents(new SpectatorListener(this), this);
        getServer().getPluginManager().registerEvents(this.privateGui, this);
        getServer().getPluginManager().registerEvents(this.kitEditor, this);

        BedFightCommand command = new BedFightCommand(this);
        register("bedfight", command);
        register("leave", command);
        register("adminkiteditor", command);

        getLogger().info("Loaded " + arenaManager.all().size() + " arena(s). Party hook: "
                + (partyHook.isAvailable() ? "OK" : "NOT FOUND"));
    }

    private void register(String name, BedFightCommand executor) {
        PluginCommand pc = getCommand(name);
        pc.setExecutor((CommandExecutor) executor);
        pc.setTabCompleter((TabCompleter) executor);
    }

    @Override
    public void onDisable() {
        if (scoreboards != null) {
            scoreboards.stop();
        }
        if (gameManager != null) {
            gameManager.shutdown();
        }
    }

    /** Reloads config.yml, settings.yml, scoreboard.yml, kit.yml and the arenas. */
    public void reloadAll() {
        ConfigMerger.merge(this, "config.yml");
        reloadConfig();
        settings.reload();
        arenaManager.loadAll();
        kitManager.load();
        scoreboards.reload();
    }

    /** Sends a prefixed message. */
    public void msg(CommandSender to, String message) {
        to.sendMessage(PREFIX + message);
    }

    public Location getLobby() {
        World w = Bukkit.getWorld(getConfig().getString("lobby-world", ""));
        if (w == null) {
            w = Bukkit.getWorlds().get(0);
        }
        return w.getSpawnLocation();
    }

    public Settings getSettings() { return settings; }
    public PartyHook getPartyHook() { return partyHook; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public GameManager getGameManager() { return gameManager; }
    public PrivateMatchGui getPrivateGui() { return privateGui; }
    public KitManager getKitManager() { return kitManager; }
    public KitEditorGui getKitEditor() { return kitEditor; }
    public SpectatorManager getSpectators() { return spectators; }
    public ScoreboardManager getScoreboards() { return scoreboards; }
}
