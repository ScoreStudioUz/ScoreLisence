package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.VanillaKitManager;
import com.scorepvp.manager.VanillaKitManager.Kit;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The vanilla multi-kit selection menu (45 slots).
 *
 * <p>Six kit slots ({@link #KIT_SLOTS}); each renders by state:
 * <ul>
 *   <li>no permission → BARRIER (locked)</li>
 *   <li>permission, no kit yet → FLOWER_BANNER_PATTERN (create)</li>
 *   <li>kit created, not active → BOOK</li>
 *   <li>kit created and active → ENCHANTED_BOOK</li>
 * </ul>
 * Opened from the hotbar kit-settings → vanilla path. Routing is by tracked
 * open-state (per-UUID), not title, so it never collides with other menus.
 */
public class VanillaKitGUI {

    public static final int SIZE = 45;
    /** The six kit slots, in menu order (matches the user's mockup). */
    public static final int[] KIT_SLOTS = {11, 13, 15, 29, 31, 33};

    private final ScorePvP plugin;
    private final java.util.Set<UUID> open = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public VanillaKitGUI(ScorePvP plugin) { this.plugin = plugin; }

    public boolean isOpen(UUID u) { return open.contains(u); }
    public void close(UUID u)     { open.remove(u); }

    /** Slot index (0..5) for a raw menu slot, or -1 if not a kit slot. */
    public int kitIndexAt(int rawSlot) {
        for (int i = 0; i < KIT_SLOTS.length; i++) if (KIT_SLOTS[i] == rawSlot) return i;
        return -1;
    }

    public String title() {
        return ColorUtil.color(plugin.getConfig().getString("vanilla-kits.title", "&8Vanilla Kitlar"));
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, SIZE, title());

        // Filler everywhere first.
        Material fillMat = mat(plugin.getConfig().getString("vanilla-kits.filler-material",
                "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
        ItemStack filler = new ItemBuilder(fillMat)
                .name(plugin.getConfig().getString("vanilla-kits.filler-name", "&r")).build();
        for (int i = 0; i < SIZE; i++) inv.setItem(i, filler);

        VanillaKitManager vk = plugin.getVanillaKitManager();
        UUID id = player.getUniqueId();
        for (int i = 0; i < KIT_SLOTS.length; i++) {
            inv.setItem(KIT_SLOTS[i], renderSlot(player, vk, id, i));
        }

        player.openInventory(inv);
        open.add(id);
    }

    private ItemStack renderSlot(Player player, VanillaKitManager vk, UUID id, int index) {
        // Locked → BARRIER with a per-slot message hint.
        if (!vk.canUseSlot(player, index)) {
            return new ItemBuilder(Material.BARRIER)
                    .name(cfg("locked-name", "&cQulflangan").replace("{n}", String.valueOf(index + 1)))
                    .lore(cfgList("locked-lore", index))
                    .build();
        }
        Kit kit = vk.getKit(id, index);
        if (kit == null) {
            // Empty but usable → create button.
            return new ItemBuilder(Material.FLOWER_BANNER_PATTERN)
                    .name(cfg("create-name", "&aYangi kit yaratish"))
                    .lore(cfgList("create-lore", index))
                    .build();
        }
        boolean selected = vk.isSelected(id, index);
        Material icon = selected ? Material.ENCHANTED_BOOK : Material.BOOK;
        String nameKey = selected ? "selected-name" : "kit-name";
        List<String> lore = new ArrayList<>();
        for (String l : plugin.getConfig().getStringList(
                "vanilla-kits." + (selected ? "selected-lore" : "kit-lore"))) {
            lore.add(l.replace("{name}", kit.getName()));
        }
        if (lore.isEmpty()) {
            lore.add("&7LKM &8- tanlash");
            lore.add("&7PKM &8- o'zgartirish");
            lore.add("&7Shift+LKM &8- nomini o'zgartirish");
            lore.add("&7Shift+PKM &8- reset");
        }
        ItemBuilder b = new ItemBuilder(icon)
                .name(cfg(nameKey, selected ? "&a{name}" : "&f{name}").replace("{name}", kit.getName()))
                .lore(lore);
        if (selected) b.glow();
        return b.build();
    }

    private String cfg(String key, String def) {
        return plugin.getConfig().getString("vanilla-kits." + key, def);
    }

    private List<String> cfgList(String key, int index) {
        List<String> raw = plugin.getConfig().getStringList("vanilla-kits." + key);
        if (raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) out.add(s.replace("{n}", String.valueOf(index + 1)));
        return out;
    }

    private static Material mat(String n, Material def) {
        try { return Material.valueOf(n.toUpperCase()); }
        catch (IllegalArgumentException e) { return def; }
    }
}
