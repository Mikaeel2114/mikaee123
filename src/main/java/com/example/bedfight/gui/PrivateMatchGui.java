package com.example.bedfight.gui;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.arena.Arena;
import com.example.bedfight.game.MatchOptions;
import com.example.bedfight.game.Mode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * /bedfight private - one single 6-row menu:
 *
 *   row 1:  title bar                         [4] party info
 *   row 2:                                    [13] map selector (left/right click = next/previous)
 *   row 3:  [20] 1v1   [22] 2v2   [24] 3v3
 *   row 5:  [38] respawn delay  [39] beds  [40] fall damage  [41] spectators  [42] time limit
 *   row 6:  [45] close   [49] START   [53] reset options
 */
public final class PrivateMatchGui implements Listener {
    public static final String PERMISSION = "bedfight.rank.mvpplusplus";
    private static final String TITLE = ChatColor.DARK_GRAY + "Private match";

    private static final int INFO = 4;
    private static final int MAP = 13;
    private static final int[] MODE_SLOTS = {20, 22, 24};
    private static final int RESPAWN = 38;
    private static final int BEDS = 39;
    private static final int FALL = 40;
    private static final int SPECS = 41;
    private static final int TIME = 42;
    private static final int CLOSE = 45;
    private static final int START = 49;
    private static final int RESET = 53;

    private final BedFightPlugin plugin;

    private static final class Session {
        /** Index into maps, -1 = random. */
        int mapIndex = -1;
        Mode mode = Mode.SOLO;
        MatchOptions options;
        List<String> maps = new ArrayList<>();
    }

    private static final class Holder implements InventoryHolder {
        final Session session;
        Inventory inventory;

        Holder(Session session) {
            this.session = session;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public PrivateMatchGui(BedFightPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        Session s = new Session();
        for (Arena a : plugin.getArenaManager().all()) {
            s.maps.add(a.getName());
        }
        if (s.maps.isEmpty()) {
            plugin.msg(p, ChatColor.RED + "There are no arenas available.");
            return;
        }
        s.options = MatchOptions.defaults(plugin.getSettings());
        // pre-select the mode that matches the party size
        int party = plugin.getPartyHook().getPartyMembers(p).size();
        for (Mode m : Mode.values()) {
            if (m.totalPlayers() == party) {
                s.mode = m;
            }
        }
        Holder holder = new Holder(s);
        Inventory inv = Bukkit.createInventory(holder, 54, TITLE);
        holder.inventory = inv;
        render(p, inv, s);
        p.openInventory(inv);
    }

    // -------------------------------------------------------------- rendering

    private void render(Player p, Inventory inv, Session s) {
        ItemStack gray = pane(7, " ");
        ItemStack accent = pane(3, " ");
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, (i < 9 || i >= 45) ? accent : gray);
        }

        int party = plugin.getPartyHook().getPartyMembers(p).size();
        boolean partyOk = party == s.mode.totalPlayers();
        inv.setItem(INFO, item(Material.NETHER_STAR, 0, ChatColor.AQUA + "" + ChatColor.BOLD + "Private Match", false,
                ChatColor.GRAY + "Configure your match, then press START.",
                "",
                ChatColor.GRAY + "Party size: " + (partyOk ? ChatColor.GREEN : ChatColor.RED) + party
                        + ChatColor.GRAY + " / " + ChatColor.WHITE + s.mode.totalPlayers() + ChatColor.GRAY + " needed"));

        // map selector
        String mapName = s.mapIndex < 0 ? "Random" : s.maps.get(s.mapIndex);
        List<String> mapLore = new ArrayList<>();
        mapLore.add(ChatColor.GRAY + "Selected: " + ChatColor.GREEN + mapName);
        mapLore.add("");
        mapLore.add(mark(s.mapIndex < 0) + "Random");
        for (int i = 0; i < s.maps.size() && i < 12; i++) {
            Arena a = plugin.getArenaManager().get(s.maps.get(i));
            String teams = a == null ? "" : ChatColor.DARK_GRAY + " (" + a.getTeams().size() + " teams)";
            mapLore.add(mark(i == s.mapIndex) + s.maps.get(i) + teams);
        }
        mapLore.add("");
        mapLore.add(ChatColor.YELLOW + "Left click: next map");
        mapLore.add(ChatColor.YELLOW + "Right click: previous map");
        inv.setItem(MAP, item(Material.MAP, 0, ChatColor.GREEN + "" + ChatColor.BOLD + "Map", false,
                mapLore.toArray(new String[0])));

        // modes
        Material[] icons = {Material.WOOD_SWORD, Material.IRON_SWORD, Material.DIAMOND_SWORD};
        Mode[] modes = Mode.values();
        for (int i = 0; i < modes.length; i++) {
            boolean sel = modes[i] == s.mode;
            inv.setItem(MODE_SLOTS[i], item(icons[i], 0,
                    (sel ? ChatColor.GREEN : ChatColor.GRAY) + "" + ChatColor.BOLD + modes[i].label(), sel,
                    ChatColor.GRAY + "Players needed in party: " + ChatColor.WHITE + modes[i].totalPlayers(),
                    "",
                    sel ? ChatColor.GREEN + "\u2714 Selected" : ChatColor.YELLOW + "Click to select"));
        }

        // options
        MatchOptions o = s.options;
        inv.setItem(RESPAWN, item(Material.GOLDEN_APPLE, 0, ChatColor.GOLD + "" + ChatColor.BOLD + "Respawn delay", false,
                ChatColor.GRAY + "Seconds before a player respawns",
                ChatColor.GRAY + "while their bed is alive.",
                "",
                ChatColor.GRAY + "Current: " + ChatColor.WHITE + o.respawnDelaySeconds + "s",
                "",
                ChatColor.YELLOW + "Left click: next  Right click: previous"));
        inv.setItem(BEDS, toggle(Material.BED, "Beds", o.bedsEnabled,
                "When disabled there are no beds:", "everyone has a single life."));
        inv.setItem(FALL, toggle(Material.FEATHER, "Fall damage", o.fallDamage,
                "Take damage when falling."));
        inv.setItem(SPECS, toggle(Material.EYE_OF_ENDER, "Spectators", o.allowSpectators,
                "Let other players watch the match", "with /bedfight spec <player>."));
        inv.setItem(TIME, item(Material.WATCH, 0, ChatColor.AQUA + "" + ChatColor.BOLD + "Time limit", false,
                ChatColor.GRAY + "The match ends in a draw",
                ChatColor.GRAY + "when the time runs out.",
                "",
                ChatColor.GRAY + "Current: " + ChatColor.WHITE + (o.timeLimitMinutes <= 0 ? "No limit" : o.timeLimitMinutes + " minutes"),
                "",
                ChatColor.YELLOW + "Left click: next  Right click: previous"));

        inv.setItem(CLOSE, item(Material.BARRIER, 0, ChatColor.RED + "" + ChatColor.BOLD + "Close", false));
        inv.setItem(RESET, item(Material.REDSTONE, 0, ChatColor.RED + "Reset options", false,
                ChatColor.GRAY + "Restore the default rules."));
        inv.setItem(START, item(Material.EMERALD_BLOCK, 0,
                (partyOk ? ChatColor.GREEN : ChatColor.RED) + "" + ChatColor.BOLD + "START MATCH", partyOk,
                ChatColor.GRAY + "Map: " + ChatColor.WHITE + mapName,
                ChatColor.GRAY + "Mode: " + ChatColor.WHITE + s.mode.label(),
                ChatColor.GRAY + "Beds: " + onOff(o.bedsEnabled) + ChatColor.GRAY + "  Fall damage: " + onOff(o.fallDamage),
                "",
                partyOk ? ChatColor.YELLOW + "Click to start!"
                        : ChatColor.RED + "Your party needs exactly " + s.mode.totalPlayers() + " players."));
    }

    private static String mark(boolean selected) {
        return selected ? ChatColor.GREEN + "\u25B6 " : ChatColor.DARK_GRAY + "  ";
    }

    private static String onOff(boolean b) {
        return b ? ChatColor.GREEN + "On" : ChatColor.RED + "Off";
    }

    private static ItemStack toggle(Material m, String name, boolean on, String... description) {
        List<String> lore = new ArrayList<>();
        for (String d : description) {
            lore.add(ChatColor.GRAY + d);
        }
        lore.add("");
        lore.add(ChatColor.GRAY + "Status: " + (on ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled"));
        lore.add("");
        lore.add(ChatColor.YELLOW + "Click to toggle");
        return item(m, 0, (on ? ChatColor.GREEN : ChatColor.RED) + "" + ChatColor.BOLD + name, on, lore.toArray(new String[0]));
    }

    private static ItemStack pane(int data, String name) {
        return item(Material.STAINED_GLASS_PANE, data, name, false);
    }

    private static ItemStack item(Material m, int data, String name, boolean glow, String... lore) {
        ItemStack it = new ItemStack(m, 1, (short) data);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            meta.setLore(Arrays.asList(lore));
        }
        if (glow) {
            meta.addEnchant(Enchantment.DURABILITY, 1, true);
        }
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    // ----------------------------------------------------------------- clicks

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
        Session s = holder.session;
        boolean backwards = e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT;
        int dir = backwards ? -1 : 1;
        boolean changed = true;

        if (slot == MAP) {
            // -1 (random) .. maps.size()-1
            int total = s.maps.size() + 1;
            s.mapIndex = Math.floorMod(s.mapIndex + 1 + dir, total) - 1;
        } else if (slot == MODE_SLOTS[0]) {
            s.mode = Mode.SOLO;
        } else if (slot == MODE_SLOTS[1]) {
            s.mode = Mode.DUO;
        } else if (slot == MODE_SLOTS[2]) {
            s.mode = Mode.TRIO;
        } else if (slot == RESPAWN) {
            List<Integer> list = plugin.getSettings().getIntList("private-match.respawn-delay-options", List.of(3, 5, 8, 10));
            s.options.respawnDelaySeconds = cycle(list, s.options.respawnDelaySeconds, dir);
        } else if (slot == TIME) {
            List<Integer> list = plugin.getSettings().getIntList("private-match.time-limit-options", List.of(0, 10, 15, 20));
            s.options.timeLimitMinutes = cycle(list, s.options.timeLimitMinutes, dir);
        } else if (slot == BEDS) {
            s.options.bedsEnabled = !s.options.bedsEnabled;
        } else if (slot == FALL) {
            s.options.fallDamage = !s.options.fallDamage;
        } else if (slot == SPECS) {
            s.options.allowSpectators = !s.options.allowSpectators;
        } else if (slot == RESET) {
            s.options = MatchOptions.defaults(plugin.getSettings());
        } else if (slot == CLOSE) {
            p.closeInventory();
            return;
        } else if (slot == START) {
            start(p, s);
            return;
        } else {
            changed = false;
        }
        if (changed) {
            render(p, e.getInventory(), s);
        }
    }

    private void start(Player p, Session s) {
        Arena arena = null;
        if (s.mapIndex >= 0) {
            arena = plugin.getArenaManager().get(s.maps.get(s.mapIndex));
            if (arena == null) {
                p.closeInventory();
                plugin.msg(p, ChatColor.RED + "That arena no longer exists.");
                return;
            }
        }
        if (plugin.getGameManager().isBusy(p)) {
            p.closeInventory();
            plugin.msg(p, ChatColor.RED + "You are already in a queue or match.");
            return;
        }
        String error = plugin.getGameManager().startPrivateMatch(p, arena, s.mode, s.options.copy());
        if (error != null) {
            plugin.msg(p, ChatColor.RED + error);
            return;
        }
        p.closeInventory();
    }

    private static int cycle(List<Integer> values, int current, int dir) {
        int idx = values.indexOf(current);
        if (idx < 0) {
            return values.get(0);
        }
        return values.get(Math.floorMod(idx + dir, values.size()));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }
}
