package com.example.bedfight.gui;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.arena.Arena;
import com.example.bedfight.game.Mode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** /bedfight private menu: pick a saved arena, then a mode (1v1 / 2v2 / 3v3). */
public final class PrivateMatchGui implements Listener {

    public static final String PERMISSION = "bedfight.rank.mvpplusplus";

    private static final String ARENA_TITLE = ChatColor.DARK_GRAY + "Private match: select map";
    private static final String MODE_TITLE = ChatColor.DARK_GRAY + "Private match: select mode";
    private static final int[] MODE_SLOTS = {11, 13, 15};
    private static final int BACK_SLOT = 22;

    private enum Type { ARENAS, MODES }

    private static final class Holder implements InventoryHolder {
        final Type type;
        final List<String> arenas; // slot -> arena name (ARENAS view)
        final String arena;        // chosen arena (MODES view)
        Inventory inventory;

        Holder(Type type, List<String> arenas, String arena) {
            this.type = type;
            this.arenas = arenas;
            this.arena = arena;
        }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private final BedFightPlugin plugin;

    public PrivateMatchGui(BedFightPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        List<String> names = new ArrayList<>();
        for (Arena a : plugin.getArenaManager().all()) {
            names.add(a.getName());
        }
        if (names.isEmpty()) {
            plugin.msg(p, ChatColor.RED + "There are no arenas available.");
            return;
        }
        int shown = Math.min(names.size(), 54);
        int rows = Math.max(1, (shown + 8) / 9);
        Holder holder = new Holder(Type.ARENAS, names.subList(0, shown), null);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, ARENA_TITLE);
        holder.inventory = inv;
        for (int i = 0; i < shown; i++) {
            Arena a = plugin.getArenaManager().get(names.get(i));
            inv.setItem(i, item(Material.PAPER, ChatColor.GREEN + a.getName(),
                    ChatColor.GRAY + "Teams: " + ChatColor.WHITE + a.getTeams().size(),
                    "",
                    ChatColor.YELLOW + "Click to select"));
        }
        p.openInventory(inv);
    }

    private void openModes(Player p, String arenaName) {
        Holder holder = new Holder(Type.MODES, null, arenaName);
        Inventory inv = Bukkit.createInventory(holder, 27, MODE_TITLE);
        holder.inventory = inv;
        Mode[] modes = Mode.values();
        Material[] icons = {Material.WOOD_SWORD, Material.IRON_SWORD, Material.DIAMOND_SWORD};
        for (int i = 0; i < modes.length; i++) {
            inv.setItem(MODE_SLOTS[i], item(icons[i], ChatColor.GREEN + modes[i].label(),
                    ChatColor.GRAY + "Map: " + ChatColor.WHITE + arenaName,
                    ChatColor.GRAY + "Players needed in party: " + ChatColor.WHITE + modes[i].totalPlayers(),
                    "",
                    ChatColor.YELLOW + "Click to start"));
        }
        inv.setItem(BACK_SLOT, item(Material.ARROW, ChatColor.RED + "Back"));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) {
            return;
        }
        if (!p.hasPermission(PERMISSION)) {
            p.closeInventory();
            plugin.msg(p, ChatColor.RED + "Only MVP++ can create private matches.");
            return;
        }

        if (holder.type == Type.ARENAS) {
            if (slot < holder.arenas.size()) {
                openModes(p, holder.arenas.get(slot));
            }
            return;
        }

        if (slot == BACK_SLOT) {
            open(p);
            return;
        }
        Mode[] modes = Mode.values();
        for (int i = 0; i < MODE_SLOTS.length; i++) {
            if (slot == MODE_SLOTS[i]) {
                Arena arena = plugin.getArenaManager().get(holder.arena);
                p.closeInventory();
                if (arena == null) {
                    plugin.msg(p, ChatColor.RED + "That arena no longer exists.");
                    return;
                }
                String error = plugin.getGameManager().startPrivateMatch(p, arena, modes[i]);
                if (error != null) {
                    plugin.msg(p, ChatColor.RED + error);
                }
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            meta.setLore(Arrays.asList(lore));
        }
        it.setItemMeta(meta);
        return it;
    }
}
