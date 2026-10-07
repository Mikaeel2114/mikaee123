package com.example.bedfight.kit;

import com.example.bedfight.BedFightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * /adminkiteditor. Layout of the 54-slot window:
 *   0-26  = main inventory rows (player slots 9-35)
 *   27-35 = hotbar (player slots 0-8)
 *   36-39 = helmet, chestplate, leggings, boots
 *   40-53 = locked decoration
 * The kit is saved when the window is closed.
 */
public final class KitEditorGui implements Listener {

    private static final String TITLE = ChatColor.DARK_GRAY + "BedFight kit editor";
    private static final int LOCKED_FROM = 40;

    private static final class Holder implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private final BedFightPlugin plugin;

    public KitEditorGui(BedFightPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 54, TITLE);
        holder.inventory = inv;

        KitManager kit = plugin.getKitManager();
        for (Map.Entry<Integer, ItemStack> e : kit.getItems().entrySet()) {
            int slot = e.getKey();
            inv.setItem(slot >= 9 ? slot - 9 : 27 + slot, e.getValue().clone());
        }
        ItemStack[] armor = kit.getArmor();
        for (int i = 0; i < 4; i++) {
            if (armor[i] != null) {
                inv.setItem(36 + i, armor[i].clone());
            }
        }

        ItemStack filler = named(new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 7), " ");
        for (int i = LOCKED_FROM; i < 54; i++) {
            inv.setItem(i, filler);
        }
        inv.setItem(49, named(new ItemStack(Material.PAPER), ChatColor.YELLOW + "How it works",
                ChatColor.GRAY + "Rows 1-3: inventory, row 4: hotbar",
                ChatColor.GRAY + "Row 5, first 4 slots: armor",
                ChatColor.GRAY + "Close the window to save the kit.",
                ChatColor.GRAY + "Wool, stained clay/glass and leather",
                ChatColor.GRAY + "armor take the team color."));
        p.openInventory(inv);
        plugin.msg(p, ChatColor.GRAY + "Arrange the kit and close the window to save it.");
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            int raw = e.getRawSlot();
            if (raw >= LOCKED_FROM && raw < 54) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            for (int raw : e.getRawSlots()) {
                if (raw >= LOCKED_FROM && raw < 54) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder) || !(e.getPlayer() instanceof Player p)) {
            return;
        }
        Inventory inv = e.getInventory();
        Map<Integer, ItemStack> items = new TreeMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() != Material.AIR) {
                items.put(i < 27 ? i + 9 : i - 27, it.clone());
            }
        }
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            ItemStack it = inv.getItem(36 + i);
            armor[i] = (it == null || it.getType() == Material.AIR) ? null : it.clone();
        }
        plugin.getKitManager().set(items, armor);
        plugin.msg(p, ChatColor.GREEN + "Kit saved (" + items.size() + " item stacks). It is given at match start and on respawn.");
    }

    private static ItemStack named(ItemStack it, String name, String... lore) {
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            meta.setLore(Arrays.asList(lore));
        }
        it.setItemMeta(meta);
        return it;
    }
}
