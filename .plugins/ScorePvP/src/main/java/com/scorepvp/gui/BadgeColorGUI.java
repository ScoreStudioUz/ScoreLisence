package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.NameStyleManager.ColorEntry;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Badge-color picker: recolors the player's chosen BADGE (nishon), not the nick.
 * Unlocked once the player owns {@code namecolor-unlock-after} badges. Lists both
 * standart (single) and gradient colors together, 27 per page. The chosen id is
 * stored in {@code PlayerSettings.badgeColor} and applied to the badge glyph via
 * {@link com.scorepvp.manager.NameStyleManager#tagPrefix(Player)}.
 */
public class BadgeColorGUI {

    public static final int SIZE = 54;
    public static final int PER_PAGE = 27;             // colors live in slots 0..26
    public static final int BACK_SLOT = 48, RESET_SLOT = 49, NEXT_SLOT = 50, EXIT_SLOT = 53;

    private final ScorePvP plugin;
    /** uuid → page currently open (only while in this menu). */
    private final Map<UUID, Integer> openPage = new ConcurrentHashMap<>();

    public BadgeColorGUI(ScorePvP plugin) { this.plugin = plugin; }

    public String title() {
        return ColorUtil.color(plugin.getConfigManager().cosStr("badgecolor.title"));
    }

    public boolean isOpen(UUID uuid) { return openPage.containsKey(uuid); }
    public int page(UUID uuid)       { Integer p = openPage.get(uuid); return p == null ? 0 : p; }
    public void close(UUID uuid)     { openPage.remove(uuid); }

    /** Every color (standart first, then gradient) in file order. */
    private List<ColorEntry> allColors() {
        return new ArrayList<>(plugin.getNameStyleManager().getColors().values());
    }

    public void open(Player player, int page) {
        List<ColorEntry> all = allColors();
        int maxPage = Math.max(0, (all.size() - 1) / PER_PAGE);
        if (page < 0) page = 0;
        if (page > maxPage) page = maxPage;

        Inventory inv = Bukkit.createInventory(player, SIZE, title());
        ItemStack divider = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").build();
        for (int i = 27; i < SIZE; i++) inv.setItem(i, divider);

        String activeId = plugin.getNameStyleManager().getBadgeColorId(player.getUniqueId());
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= all.size()) break;
            ColorEntry c = all.get(idx);
            inv.setItem(i, swatch(player, c, c.id().equals(activeId)));
        }

        if (page > 0) {
            inv.setItem(BACK_SLOT, new ItemBuilder(Material.ARROW)
                    .name(plugin.getConfigManager().cosStr("badgecolor.back"))
                    .customModelData(9002).build());
        }
        inv.setItem(RESET_SLOT, new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().cosStr("badgecolor.reset")).build());
        if (page < maxPage) {
            inv.setItem(NEXT_SLOT, new ItemBuilder(Material.ARROW)
                    .name(plugin.getConfigManager().cosStr("badgecolor.next"))
                    .customModelData(9001).build());
        }
        inv.setItem(EXIT_SLOT, new ItemBuilder(Material.RED_DYE)
                .name(plugin.getConfigManager().cosStr("badgecolor.exit")).build());

        player.openInventory(inv);
        openPage.put(player.getUniqueId(), page);
    }

    /** A swatch previews the player's chosen badge glyph painted in this color. */
    private ItemStack swatch(Player player, ColorEntry c, boolean active) {
        boolean owned = plugin.getNameStyleManager().canUse(player, c);
        // Preview: paint a sample glyph (the player's active badge if any) in this color.
        var st = plugin.getSettingsManager().get(player.getUniqueId());
        var tag = st != null ? plugin.getNameStyleManager().getTag(st.getNameTag()) : null;
        String glyph = tag != null ? ColorUtil.strip(tag.tag()) : "●";
        String preview = owned
                ? ColorUtil.applyColorSpec(c.colorSpec(), glyph)
                : "&8" + glyph;
        List<String> lore = new ArrayList<>();
        if (active)     lore.add(plugin.getConfigManager().cosStr("badgecolor.lore-active"));
        else if (owned) lore.add(plugin.getConfigManager().cosStr("badgecolor.lore-available"));
        else            lore.add(plugin.getConfigManager().cosStr("badgecolor.lore-locked"));
        ItemBuilder b = new ItemBuilder(owned ? c.icon() : Material.GRAY_DYE)
                .name(preview).lore(lore);
        if (active) b.glow();
        return b.build();
    }

    /** The color id at a clicked slot on the current page, or null. */
    public String colorIdAt(Player player, int slot) {
        if (slot < 0 || slot >= PER_PAGE) return null;
        Integer p = openPage.get(player.getUniqueId());
        if (p == null) return null;
        List<ColorEntry> all = allColors();
        int idx = p * PER_PAGE + slot;
        if (idx >= all.size()) return null;
        return all.get(idx).id();
    }

    public boolean hasNextPage(UUID uuid) {
        Integer p = openPage.get(uuid);
        if (p == null) return false;
        int maxPage = Math.max(0, (allColors().size() - 1) / PER_PAGE);
        return p < maxPage;
    }
}
