package com.example.bedfight.spectate;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.game.Match;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;

/**
 * Custom spectator mode.
 *
 * Vanilla SPECTATOR game mode is NOT used, because it lets players noclip through blocks and shows
 * the built-in spectator hotbar menu. Instead a spectator is an ADVENTURE-mode player that can fly
 * (so it collides with blocks like any flying player), is hidden from the people fighting, can't
 * interact with anything, and is kept inside the arena area by Match.onMove.
 */
public final class SpectatorManager {
    public static final int TELEPORTER_SLOT = 0;
    public static final int LEAVE_SLOT = 8;
    public static final String TELEPORTER_TITLE = ChatColor.DARK_GRAY + "Teleporter";

    private final BedFightPlugin plugin;
    private final Map<UUID, Match> states = new HashMap<>();

    public SpectatorManager(BedFightPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean is(Player p) { return states.containsKey(p.getUniqueId()); }
    public Match matchOf(Player p) { return states.get(p.getUniqueId()); }

    /** items = false for the short "waiting to respawn" state (no hotbar items). */
    public void enable(Player p, Match match, boolean items) {
        states.put(p.getUniqueId(), match);

        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(pe.getType());
        }
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20.0F);
        p.setFireTicks(0);
        p.setFallDistance(0.0F);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setFlySpeed((float) Math.max(0.01D, Math.min(1.0D, plugin.getSettings().getDouble("spectator.fly-speed", 0.1D))));
        setCollides(p, false);

        // invisible to the people fighting, visible to other spectators
        for (Player other : match.viewers()) {
            if (other.equals(p)) {
                continue;
            }
            if (is(other)) {
                p.showPlayer(other);
                other.showPlayer(p);
            } else {
                other.hidePlayer(p);
            }
        }

        if (items) {
            if (plugin.getSettings().getBoolean("spectator.teleporter-item", true)) {
                p.getInventory().setItem(TELEPORTER_SLOT,
                        named(Material.COMPASS, ChatColor.GREEN + "Teleporter", ChatColor.GRAY + "Right click to pick a player"));
            }
            if (plugin.getSettings().getBoolean("spectator.leave-item", true)) {
                p.getInventory().setItem(LEAVE_SLOT,
                        named(Material.BED, ChatColor.RED + "Leave", ChatColor.GRAY + "Right click to leave the match"));
            }
            p.getInventory().setHeldItemSlot(0);
        }
        p.updateInventory();
    }

    /** Safe to call on anybody: does nothing when the player is not a spectator. */
    public void disable(Player p) {
        if (states.remove(p.getUniqueId()) == null) {
            return;
        }
        p.setFlying(false);
        p.setAllowFlight(false);
        p.setFlySpeed(0.1F);
        p.setGameMode(GameMode.SURVIVAL);
        setCollides(p, true);
        for (Player other : Bukkit.getOnlinePlayers()) {
            other.showPlayer(p);
        }
    }

    public boolean isTeleporterItem(ItemStack it) {
        return it != null && it.getType() == Material.COMPASS && hasName(it, ChatColor.GREEN + "Teleporter");
    }

    public boolean isLeaveItem(ItemStack it) {
        return it != null && it.getType() == Material.BED && hasName(it, ChatColor.RED + "Leave");
    }

    // -------------------------------------------------------------- teleporter

    public static final class TeleporterHolder implements InventoryHolder {
        public final List<UUID> targets = new ArrayList<>();
        public Inventory inventory;

        @Override
        public Inventory getInventory() { return inventory; }
    }

    public void openTeleporter(Player spectator) {
        Match m = matchOf(spectator);
        if (m == null) {
            return;
        }
        List<Player> alive = m.activePlayers();
        if (alive.isEmpty()) {
            plugin.msg(spectator, ChatColor.RED + "Nobody is alive to teleport to.");
            return;
        }
        TeleporterHolder holder = new TeleporterHolder();
        int size = Math.min(54, Math.max(9, ((alive.size() + 8) / 9) * 9));
        Inventory inv = Bukkit.createInventory(holder, size, TELEPORTER_TITLE);
        holder.inventory = inv;
        for (int i = 0; i < alive.size() && i < size; i++) {
            Player t = alive.get(i);
            holder.targets.add(t.getUniqueId());
            ItemStack head = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwner(t.getName());
            meta.setDisplayName(ChatColor.YELLOW + t.getName());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Health: " + ChatColor.RED + (int) Math.ceil(t.getHealth() / 2.0D) + "\u2764");
            lore.add("");
            lore.add(ChatColor.GREEN + "Click to teleport");
            meta.setLore(lore);
            head.setItemMeta(meta);
            inv.setItem(i, head);
        }
        spectator.openInventory(inv);
    }

    // ----------------------------------------------------------------- helpers

    private static ItemStack named(Material m, String name, String lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        List<String> l = new ArrayList<>();
        l.add(lore);
        meta.setLore(l);
        it.setItemMeta(meta);
        return it;
    }

    private static boolean hasName(ItemStack it, String name) {
        ItemMeta meta = it.getItemMeta();
        return meta != null && name.equals(meta.getDisplayName());
    }

    /** Stops spectators from pushing / being hit by players and arrows. Uses reflection: the method only exists on Spigot. */
    private static void setCollides(Player p, boolean value) {
        try {
            Object spigot = p.getClass().getMethod("spigot").invoke(p);
            for (Object target : new Object[] {p, spigot}) {
                try {
                    target.getClass().getMethod("setCollidesWithEntities", boolean.class).invoke(target, value);
                    return;
                } catch (NoSuchMethodException ignored) {
                    // try the next object
                }
            }
        } catch (Exception ignored) {
            // not supported on this server build - hiding the player is still enough
        }
    }
}
