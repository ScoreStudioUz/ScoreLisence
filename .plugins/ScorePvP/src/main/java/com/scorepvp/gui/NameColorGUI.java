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
 * Paginated name-color picker, shared by the standart (legacy/hex) and premium
 * (gradient) variants. 54-slot layout matching the user's sample:
 * <pre>
 *   0..35   color swatches (up to 36 per page)
 *   36..44  divider row (gray glass)
 *   48      ← back   49 reset   50 next →   53 exit
 * </pre>
 * The chosen color is applied immediately everywhere via {@code %scorepvp_name%}
 * (chat) and TAB — no relog needed. Colors the player lacks permission for show
 * as locked. Standart and gradient share one active slot ({@code nameColor}),
 * so selecting in either replaces the other.
 */
public class NameColorGUI {

    public static final int SIZE = 54;
    public static final int PER_PAGE = 36;
    public static final int BACK_SLOT = 48, RESET_SLOT = 49, NEXT_SLOT = 50, EXIT_SLOT = 53;

    private final ScorePvP plugin;
    /** uuid → page currently open (only while in this menu). */
    private final Map<UUID, int[]> openState = new ConcurrentHashMap<>(); // [premium(0/1), page]

    public NameColorGUI(ScorePvP plugin) { this.plugin = plugin; }

    public String title(boolean premium) {
        return ColorUtil.color(premium
                ? plugin.getConfigManager().cosStr("namecolor.premium-title")
                : plugin.getConfigManager().cosStr("namecolor.standart-title"));
    }

    public boolean isPremiumScreen(UUID uuid) { int[] s = openState.get(uuid); return s != null && s[0] == 1; }
    public boolean isOpen(UUID uuid)          { return openState.containsKey(uuid); }
    public int page(UUID uuid)                { int[] s = openState.get(uuid); return s != null ? s[1] : 0; }
    public void close(UUID uuid)              { openState.remove(uuid); }

    /** Ordered entries for this variant (premium = gradient file, else standart). */
    private List<ColorEntry> entriesFor(boolean premium) {
        List<ColorEntry> out = new ArrayList<>();
        for (ColorEntry c : plugin.getNameStyleManager().getColors().values()) {
            if (c.premium() == premium) out.add(c);
        }
        return out;
    }

    public void open(Player player, boolean premium, int page) {
        List<ColorEntry> all = entriesFor(premium);
        int maxPage = Math.max(0, (all.size() - 1) / PER_PAGE);
        if (page < 0) page = 0;
        if (page > maxPage) page = maxPage;

        Inventory inv = Bukkit.createInventory(player, SIZE, title(premium));

        // Divider row.
        ItemStack divider = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").build();
        for (int i = 36; i <= 44; i++) inv.setItem(i, divider);

        String activeId = activeColorId(player);
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= all.size()) break;
            ColorEntry c = all.get(idx);
            inv.setItem(i, swatch(player, c, c.id().equals(activeId)));
        }

        inv.setItem(BACK_SLOT, new ItemBuilder(Material.ARROW)
                .name(plugin.getConfigManager().cosStr("namecolor.back")).build());
        inv.setItem(RESET_SLOT, new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().cosStr("namecolor.reset")).build());
        if (page < maxPage) {
            inv.setItem(NEXT_SLOT, new ItemBuilder(Material.ARROW)
                    .name(plugin.getConfigManager().cosStr("namecolor.next"))
                    .customModelData(9001).build());
        }
        inv.setItem(EXIT_SLOT, new ItemBuilder(Material.RED_DYE)
                .name(plugin.getConfigManager().cosStr("namecolor.exit")).build());

        player.openInventory(inv);
        openState.put(player.getUniqueId(), new int[]{premium ? 1 : 0, page});
    }

    /** A single color swatch: shows the player's nick painted in that color. */
    private ItemStack swatch(Player player, ColorEntry c, boolean active) {
        boolean owned = plugin.getNameStyleManager().canUse(player, c);
        String preview = owned
                ? ColorUtil.applyColorSpec(c.colorSpec(), player.getName())
                : "&8" + player.getName();
        List<String> lore = new ArrayList<>();
        if (active)       lore.add(plugin.getConfigManager().cosStr("namecolor.lore-active"));
        else if (owned)   lore.add(plugin.getConfigManager().cosStr("namecolor.lore-available"));
        else              lore.add(plugin.getConfigManager().cosStr("namecolor.lore-locked"));
        ItemBuilder b = new ItemBuilder(owned ? c.icon() : Material.GRAY_DYE)
                .name(preview).lore(lore);
        if (active) b.glow();
        return b.build();
    }

    /** The id of the color this slot represents, or null. Only valid while the menu is open. */
    public String colorIdAt(Player player, int slot) {
        if (slot < 0 || slot >= PER_PAGE) return null;
        int[] s = openState.get(player.getUniqueId());
        if (s == null) return null;
        List<ColorEntry> all = entriesFor(s[0] == 1);
        int idx = s[1] * PER_PAGE + slot;
        if (idx >= all.size()) return null;
        return all.get(idx).id();
    }

    public boolean hasNextPage(UUID uuid) {
        int[] s = openState.get(uuid);
        if (s == null) return false;
        int size = entriesFor(s[0] == 1).size();
        int maxPage = Math.max(0, (size - 1) / PER_PAGE);
        return s[1] < maxPage;
    }

    /** The player's currently-active color id (shared between standart + premium). */
    private String activeColorId(Player player) {
        var st = plugin.getSettingsManager().get(player.getUniqueId());
        return st != null ? st.getNameColor() : null;
    }
}
