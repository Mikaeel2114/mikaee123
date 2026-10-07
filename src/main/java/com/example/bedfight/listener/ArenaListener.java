package com.example.bedfight.listener;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.arena.ArenaManager;
import com.example.bedfight.game.Match;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Dispatches world events to running matches and protects arena (template) worlds. */
public final class ArenaListener implements Listener {

    private static final String PROTECTED_MSG =
            "This arena is protected. Use /bedfight buildmode to edit it.";

    private final BedFightPlugin plugin;

    public ArenaListener(BedFightPlugin plugin) {
        this.plugin = plugin;
    }

    private Match match(World w) { return plugin.getGameManager().getMatchByWorld(w); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        Match m = match(e.getBlock().getWorld());
        if (m != null) {
            m.onBreak(e);
            return;
        }
        ArenaManager am = plugin.getArenaManager();
        if (am.isTemplateWorld(e.getBlock().getWorld()) && !am.isBuildMode(e.getPlayer())) {
            e.setCancelled(true);
            plugin.msg(e.getPlayer(), PROTECTED_MSG);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        Match m = match(e.getBlock().getWorld());
        if (m != null) {
            m.onPlace(e);
            return;
        }
        ArenaManager am = plugin.getArenaManager();
        if (am.isTemplateWorld(e.getBlock().getWorld()) && !am.isBuildMode(e.getPlayer())) {
            e.setCancelled(true);
            plugin.msg(e.getPlayer(), PROTECTED_MSG);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        World w = e.getEntity().getWorld();
        Match m = match(w);
        if (m != null) {
            m.onExplode(e);
        } else if (plugin.getArenaManager().isTemplateWorld(w)) {
            e.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        Match m = match(e.getEntity().getWorld());
        if (m != null) {
            m.onDamage(e);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Match m = match(e.getPlayer().getWorld());
        if (m != null) {
            m.onMove(e);
        }
    }

    /** Players can never drop items (accidental Q presses, clicking outside the inventory). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (match(e.getPlayer().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(PlayerPickupItemEvent e) {
        if (match(e.getPlayer().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    /** No item entities at all in match worlds (broken blocks never drop wool etc.). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (match(e.getLocation().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (match(e.getEntity().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getGameManager().leaveQueue(e.getPlayer());
        plugin.getArenaManager().onQuit(e.getPlayer());
        Match m = plugin.getGameManager().getMatch(e.getPlayer());
        if (m != null) {
            m.onQuit(e.getPlayer());
        }
    }
}
