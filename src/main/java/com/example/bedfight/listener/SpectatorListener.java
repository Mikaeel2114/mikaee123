package com.example.bedfight.listener;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.game.Match;
import com.example.bedfight.spectate.SpectatorManager;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Everything a spectator is NOT allowed to do, plus the hotbar items (teleporter / leave). */
public final class SpectatorListener implements Listener {
    private final BedFightPlugin plugin;

    public SpectatorListener(BedFightPlugin plugin) {
        this.plugin = plugin;
    }

    private SpectatorManager specs() {
        return plugin.getSpectators();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!specs().is(p)) {
            return;
        }
        e.setCancelled(true);
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack hand = p.getItemInHand();
        if (specs().isTeleporterItem(hand)) {
            specs().openTeleporter(p);
        } else if (specs().isLeaveItem(hand)) {
            plugin.getGameManager().leaveAny(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (specs().is(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        HumanEntity who = e.getWhoClicked();
        if (!(who instanceof Player p)) {
            return;
        }
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder instanceof SpectatorManager.TeleporterHolder th) {
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (slot < 0 || slot >= th.targets.size()) {
                return;
            }
            UUID id = th.targets.get(slot);
            Player target = Bukkit.getPlayer(id);
            Match m = specs().matchOf(p);
            p.closeInventory();
            if (target == null || m == null || !target.getWorld().equals(p.getWorld())) {
                plugin.msg(p, "That player is no longer available.");
                return;
            }
            p.teleport(target.getLocation().clone().add(0, 2, 0));
            return;
        }
        if (specs().is(p)) {
            // no moving items around in the spectator hotbar / crafting grid
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p
                && (specs().is(p) || e.getInventory().getHolder() instanceof SpectatorManager.TeleporterHolder)) {
            e.setCancelled(true);
        }
    }

    /** Mobs never target spectators. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetEvent e) {
        if (e.getTarget() instanceof Player p && specs().is(p)) {
            e.setCancelled(true);
        }
    }
}
