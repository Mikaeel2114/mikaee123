package com.example.bedfight.kit;

import com.example.bedfight.BedFightPlugin;
import com.example.bedfight.util.YamlFiles;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;

/**
 * The kit given to every player when a match starts and when they respawn.
 * Stored in plugins/BedFight/kit.yml and edited in game with /adminkiteditor.
 * Wool / stained clay / stained glass / carpet and leather armor are re-colored to the team color.
 */
public final class KitManager {

    private static final String[] ARMOR_KEYS = {"helmet", "chestplate", "leggings", "boots"};

    private final BedFightPlugin plugin;
    private final File file;
    /** Inventory slot (0-8 hotbar, 9-35 main) -> item. */
    private final Map<Integer, ItemStack> items = new TreeMap<>();
    /** helmet, chestplate, leggings, boots */
    private final ItemStack[] armor = new ItemStack[4];

    public KitManager(BedFightPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kit.yml");
        load();
    }

    public void load() {
        if (!file.isFile()) {
            resetToDefault();
            return;
        }
        items.clear();
        for (int i = 0; i < 4; i++) {
            armor[i] = null;
        }
        YamlConfiguration y = YamlFiles.load(file);
        ConfigurationSection slots = y.getConfigurationSection("slots");
        if (slots != null) {
            for (String key : slots.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    ItemStack it = slots.getItemStack(key);
                    if (it != null && slot >= 0 && slot < 36) {
                        items.put(slot, it);
                    }
                } catch (NumberFormatException ignored) {
                    // skip bad key
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            armor[i] = y.getItemStack("armor." + ARMOR_KEYS[i]);
        }
    }

    /** Default kit (Minemen Club BedFight): iron sword, 64 wool, shears, golden apple, leather armor. */
    public void resetToDefault() {
        items.clear();
        items.put(0, new ItemStack(Material.IRON_SWORD));
        items.put(1, new ItemStack(Material.WOOL, 64));
        items.put(2, new ItemStack(Material.SHEARS));
        items.put(3, new ItemStack(Material.GOLDEN_APPLE, 1));
        armor[0] = new ItemStack(Material.LEATHER_HELMET);
        armor[1] = new ItemStack(Material.LEATHER_CHESTPLATE);
        armor[2] = new ItemStack(Material.LEATHER_LEGGINGS);
        armor[3] = new ItemStack(Material.LEATHER_BOOTS);
        save();
    }

    public Map<Integer, ItemStack> getItems() { return items; }

    public ItemStack[] getArmor() { return armor; }

    /** Replaces the kit and writes kit.yml. */
    public void set(Map<Integer, ItemStack> newItems, ItemStack[] newArmor) {
        items.clear();
        for (Map.Entry<Integer, ItemStack> e : newItems.entrySet()) {
            items.put(e.getKey(), e.getValue().clone());
        }
        for (int i = 0; i < 4; i++) {
            armor[i] = newArmor[i] == null ? null : newArmor[i].clone();
        }
        save();
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<Integer, ItemStack> e : items.entrySet()) {
            y.set("slots." + e.getKey(), e.getValue());
        }
        for (int i = 0; i < 4; i++) {
            if (armor[i] != null) {
                y.set("armor." + ARMOR_KEYS[i], armor[i]);
            }
        }
        try {
            YamlFiles.save(y, file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save kit.yml", ex);
        }
    }

    /** Gives the kit to the player (inventory is cleared first). */
    public void apply(Player p, DyeColor color) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        for (Map.Entry<Integer, ItemStack> e : items.entrySet()) {
            inv.setItem(e.getKey(), tint(e.getValue(), color));
        }
        inv.setHelmet(armor[0] == null ? null : tint(armor[0], color));
        inv.setChestplate(armor[1] == null ? null : tint(armor[1], color));
        inv.setLeggings(armor[2] == null ? null : tint(armor[2], color));
        inv.setBoots(armor[3] == null ? null : tint(armor[3], color));
        inv.setHeldItemSlot(0);
    }

    private static ItemStack tint(ItemStack src, DyeColor color) {
        ItemStack it = src.clone();
        Material m = it.getType();
        if (m == Material.WOOL || m == Material.STAINED_CLAY || m == Material.STAINED_GLASS
                || m == Material.STAINED_GLASS_PANE || m == Material.CARPET) {
            it.setDurability((short) color.getWoolData());
        } else if (m.name().startsWith("LEATHER_")) {
            ItemMeta meta = it.getItemMeta();
            if (meta instanceof LeatherArmorMeta lm) {
                lm.setColor(color.getColor());
                it.setItemMeta(lm);
            }
        }
        return it;
    }
}
