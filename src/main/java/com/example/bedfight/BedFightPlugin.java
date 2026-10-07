package com.example.bedfight;

import com.example.bedfight.arena.ArenaManager;
import com.example.bedfight.command.BedFightCommand;
import com.example.bedfight.game.GameManager;
import com.example.bedfight.gui.PrivateMatchGui;
import com.example.bedfight.hook.PartyHook;
import com.example.bedfight.kit.KitEditorGui;
import com.example.bedfight.kit.KitManager;
import com.example.bedfight.scoreboard.ScoreboardManager;
import com.example.bedfight.listener.ArenaListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class BedFightPlugin extends JavaPlugin {

    public static final String PREFIX =
            ChatColor.RED + "" + ChatColor.BOLD + "BedFight " + ChatColor.GRAY + "» " + ChatColor.RESET;

    private PartyHook partyHook;
    private ArenaManager arenaManager;
    private GameManager gameManager;
    private PrivateMatchGui privateGui;
    private KitManager kitManager;
    private KitEditorGui kitEditor;
    private ScoreboardManager scoreboards;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        partyHook = new PartyHook(this);
        arenaManager = new ArenaManager(this);
        arenaManager.loadAll();
        gameManager = new GameManager(this);
        privateGui = new PrivateMatchGui(this);
        kitManager = new KitManager(this);
        kitEditor = new KitEditorGui(this);
        scoreboards = new ScoreboardManager(this);

        getServer().getPluginManager().registerEvents(new ArenaListener(this), this);
        getServer().getPluginManager().registerEvents(privateGui, this);
        getServer().getPluginManager().registerEvents(kitEditor, this);

        BedFightCommand command = new BedFightCommand(this);
        PluginCommand pc = getCommand("bedfight");
        pc.setExecutor(command);
        pc.setTabCompleter(command);
        PluginCommand leave = getCommand("leave");
        leave.setExecutor(command);
        getCommand("adminkiteditor").setExecutor(command);
        getCommand("adminkiteditor").setTabCompleter(command);
        getCommand("beddefend").setExecutor(command);
        getCommand("beddefend").setTabCompleter(command);

        getLogger().info("Loaded " + arenaManager.all().size() + " arena(s). Party hook: "
                + (partyHook.isAvailable() ? "OK" : "NOT FOUND"));
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

    public void msg(CommandSender to, String message) {
        to.sendMessage(PREFIX + message);
    }

    /** Lobby location players are sent to after a match. */
    public Location getLobby() {
        World w = Bukkit.getWorld(getConfig().getString("lobby-world", ""));
        if (w == null) {
            w = Bukkit.getWorlds().get(0);
        }
        return w.getSpawnLocation();
    }

    public PartyHook getPartyHook() { return partyHook; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public GameManager getGameManager() { return gameManager; }
    public PrivateMatchGui getPrivateGui() { return privateGui; }
    public KitManager getKitManager() { return kitManager; }
    public KitEditorGui getKitEditor() { return kitEditor; }
    public ScoreboardManager getScoreboards() { return scoreboards; }
}
