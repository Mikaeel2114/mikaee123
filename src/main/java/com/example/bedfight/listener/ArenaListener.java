package com.example.bedfight.listener;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.arena.ArenaManager;
import com.example.bedfight.game.Match;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Routes world events to the running Match and enforces the map protection rules:
 *  - match worlds: only player-placed blocks can be broken / blown up, nothing else can change the map
 *  - template (arena) worlds: protected unless the admin is in /bedfight buildmode
 */
public final class ArenaListener implements Listener {
    private static final String PROTECTED_MSG = "This arena is protected. Use /bedfight buildmode to edit it.";
    private final BedFightPlugin plugin;

    public ArenaListener(BedFightPlugin plugin) {
        this.plugin = plugin;
    }

    private Match match(World w) {
        return plugin.getGameManager().getMatchByWorld(w);
    }

    // ------------------------------------------------------------ blocks

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

    /** Buckets would create liquid blocks the tracker can't follow: not allowed in matches. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (match(e.getPlayer().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (match(e.getPlayer().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    /** Pistons could push map blocks around (and out of the protected set). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (match(e.getBlock().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (match(e.getBlock().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBurn(BlockBurnEvent e) {
        if (match(e.getBlock().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onIgnite(BlockIgniteEvent e) {
        if (match(e.getBlock().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLeavesDecay(LeavesDecayEvent e) {
        if (match(e.getBlock().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingBreak(HangingBreakEvent e) {
        if (match(e.getEntity().getWorld()) != null) {
            e.setCancelled(true);
        }
    }

    /** Sand/gravel: map blocks must not fall away, placed ones stay tracked when they land. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Match m = match(e.getBlock().getWorld());
        if (m == null) {
            return;
        }
        if (e.getEntity() instanceof FallingBlock) {
            if (e.getTo() == Material.AIR) {
                // a block turns into a falling block
                if (m.isPlacedBlock(e.getBlock())) {
                    m.unmarkPlaced(e.getBlock());
                } else {
                    e.setCancelled(true);
                }
            } else {
                // a falling block lands
                m.markPlaced(e.getBlock());
            }
        } else {
            e.setCancelled(true);
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

    /** Farmland trampling etc. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPhysicalInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL && match(e.getPlayer().getWorld()) != null) {
            e.setCancelled(true);
            e.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    // ---------------------------------------------------------- players

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
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // The match world was deleted while the player was offline: send them to the lobby
        if (p.getWorld().getName().startsWith("bf_") && match(p.getWorld()) == null) {
            p.teleport(plugin.getLobby());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getArenaManager().onQuit(e.getPlayer());
        plugin.getGameManager().handleQuit(e.getPlayer());
        plugin.getScoreboards().remove(e.getPlayer());
    }
}
