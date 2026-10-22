package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.NameStyleManager;
import com.scorepvp.manager.NameStyleManager.TagEntry;
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
 * Nishonlar (badges) menu + purchase confirm.
 *
 * <p>Badges are emoji prefixes bought with silver. The list is 54-slot; each
 * badge sits at its configured slot, shown as:
 * <ul>
 *   <li>active (selected)  → "Yoqilgan" + glow</li>
 *   <li>owned but inactive → "Mavjud"</li>
 *   <li>locked (not bought)→ gray dye, "Ochilmagan" + price</li>
 * </ul>
 * Clicking an owned badge toggles it active. Clicking a locked badge opens the
 * 27-slot confirm screen (cancel / price / accept). Buying debits silver and
 * grants the badge. A "Rang tanlagich" gate opens the name-color menu once the
 * player owns {@code namecolor-unlock-after} badges.
 *
 * Routing is by tracked screen-state (list vs confirm), not title, so shared
 * titles never collide.
 */
public class BadgeGUI {

    public static final int LIST_SIZE = 54;
    public static final int CONFIRM_SIZE = 27;

    // list control slots (from the user's sample)
    public static final int LIST_RESET_SLOT      = 45;
    public static final int LIST_COLORPICK_SLOT  = 49;
    public static final int LIST_EXIT_SLOT       = 53;
    public static final int LIST_NEXT_SLOT       = 52; // → next page

    // confirm slots
    public static final int CONFIRM_CANCEL_SLOT  = 11;
    public static final int CONFIRM_INFO_SLOT    = 13;
    public static final int CONFIRM_ACCEPT_SLOT  = 15;

    /** Badge content slots (rows 2..4, cols 2..8): 10..16, 19..25, 28..34. */
    public static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    public static final int BADGES_PER_PAGE = CONTENT_SLOTS.length; // 21

    private static boolean isContentSlot(int slot) {
        for (int s : CONTENT_SLOTS) if (s == slot) return true;
        return false;
    }

    public static final int PER_PAGE_MAX = BADGES_PER_PAGE; // legacy alias

    private static final int SCREEN_LIST = 1, SCREEN_CONFIRM = 2;

    private final ScorePvP plugin;
    /** uuid → [screen, page] while a badge menu is open. */
    private final Map<UUID, int[]> state = new ConcurrentHashMap<>();
    /** uuid → badge id being confirmed for purchase. */
    private final Map<UUID, String> confirming = new ConcurrentHashMap<>();

    public BadgeGUI(ScorePvP plugin) { this.plugin = plugin; }

    public String listTitle()    { return ColorUtil.color(plugin.getConfigManager().cosStr("badges.list-title")); }
    public String confirmTitle() { return ColorUtil.color(plugin.getConfigManager().cosStr("badges.confirm-title")); }

    public boolean isListScreen(UUID u)    { int[] s = state.get(u); return s != null && s[0] == SCREEN_LIST; }
    public boolean isConfirmScreen(UUID u) { int[] s = state.get(u); return s != null && s[0] == SCREEN_CONFIRM; }
    public boolean isOpen(UUID u)          { return state.containsKey(u); }
    public int page(UUID u)                { int[] s = state.get(u); return s != null ? s[1] : 0; }
    public void clear(UUID u)              { state.remove(u); }
    public String confirmingId(UUID u)     { return confirming.get(u); }

    /** Badges ordered by their configured slot (for pagination). */
    private List<TagEntry> badges() {
        List<TagEntry> out = new ArrayList<>(plugin.getNameStyleManager().getTags().values());
        out.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        return out;
    }

    // ── List screen ───────────────────────────────────────────────────────────

    public void openList(Player player, int page) {
        NameStyleManager ns = plugin.getNameStyleManager();
        List<TagEntry> all = badges();
        // Paginate purely by count: PER_PAGE_MAX badges per page, placed at their
        // own configured slot for page 0; further pages reuse the same slots.
        int maxPage = Math.max(0, (all.size() - 1) / PER_PAGE_MAX);
        if (page < 0) page = 0;
        if (page > maxPage) page = maxPage;

        Inventory inv = Bukkit.createInventory(player, LIST_SIZE, listTitle());
        // Fill every non-content slot with gray glass (border + control row bg),
        // matching the user's layout so the menu never has bare black holes.
        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").build();
        for (int i = 0; i < LIST_SIZE; i++) if (!isContentSlot(i)) inv.setItem(i, filler);

        var st = plugin.getSettingsManager().get(player.getUniqueId());
        String activeId = st != null ? st.getNameTag() : null;

        // Badges sit at their configured slots (10..16, 19..25, 28..34). Page > 0
        // reuses the same slots for the next block of badges.
        int start = page * BADGES_PER_PAGE;
        for (int i = 0; i < BADGES_PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= all.size()) break;
            TagEntry t = all.get(idx);
            int slot = CONTENT_SLOTS[i];
            inv.setItem(slot, badgeIcon(player, t, t.id().equals(activeId)));
        }

        inv.setItem(LIST_RESET_SLOT, new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().cosStr("badges.reset")).build());
        inv.setItem(LIST_EXIT_SLOT, new ItemBuilder(Material.RED_DYE)
                .name(plugin.getConfigManager().cosStr("badges.exit")).build());

        // Color-picker gate.
        int owned = ns.ownedBadgeCount(player);
        int needed = ns.getNamecolorUnlockAfter();
        if (owned >= needed) {
            inv.setItem(LIST_COLORPICK_SLOT, new ItemBuilder(Material.PINK_DYE)
                    .name(plugin.getConfigManager().cosStr("badges.colorpick-open"))
                    .glow().build());
        } else {
            inv.setItem(LIST_COLORPICK_SLOT, new ItemBuilder(Material.GRAY_DYE)
                    .name(plugin.getConfigManager().cosStr("badges.colorpick-name"))
                    .lore(plugin.getConfigManager().cosStr("badges.colorpick-locked")
                            .replace("{needed}", String.valueOf(needed))
                            .replace("{owned}", String.valueOf(owned)))
                    .build());
        }

        if (page < maxPage) {
            inv.setItem(LIST_NEXT_SLOT, new ItemBuilder(Material.ARROW)
                    .name(plugin.getConfigManager().cosStr("badges.next"))
                    .customModelData(9001).build());
        }

        player.openInventory(inv);
        state.put(player.getUniqueId(), new int[]{SCREEN_LIST, page});
    }

    private ItemStack badgeIcon(Player player, TagEntry t, boolean active) {
        boolean owned = plugin.getNameStyleManager().ownsBadge(player, t);
        List<String> lore = new ArrayList<>();
        Material icon;
        if (active) {
            icon = Material.LIME_DYE;
            lore.add(plugin.getConfigManager().cosStr("badges.lore-active"));
        } else if (owned) {
            icon = Material.WHITE_DYE;
            lore.add(plugin.getConfigManager().cosStr("badges.lore-owned"));
        } else {
            icon = Material.GRAY_DYE;
            lore.add(plugin.getConfigManager().cosStr("badges.lore-locked"));
            lore.add(plugin.getConfigManager().cosStr("badges.lore-price")
                    .replace("{price}", String.valueOf(t.price())));
        }
        ItemBuilder b = new ItemBuilder(icon).name(t.name()).lore(lore);
        if (active) b.glow();
        return b.build();
    }

    /** The badge id at a clicked list slot on the current page, or null. */
    public String badgeIdAt(Player player, int slot) {
        int[] s = state.get(player.getUniqueId());
        if (s == null) return null;
        int contentIdx = -1;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) if (CONTENT_SLOTS[i] == slot) { contentIdx = i; break; }
        if (contentIdx < 0) return null;
        int page = s[1];
        int idx = page * BADGES_PER_PAGE + contentIdx;
        List<TagEntry> all = badges();
        if (idx < 0 || idx >= all.size()) return null;
        return all.get(idx).id();
    }

    public boolean hasNextPage(UUID uuid) {
        int[] s = state.get(uuid);
        if (s == null) return false;
        int maxPage = Math.max(0, (badges().size() - 1) / BADGES_PER_PAGE);
        return s[1] < maxPage;
    }

    // ── Confirm screen ────────────────────────────────────────────────────────

    public void openConfirm(Player player, String badgeId) {
        TagEntry t = plugin.getNameStyleManager().getTag(badgeId);
        if (t == null) { openList(player, page(player.getUniqueId())); return; }
        // Capture the list page BEFORE openInventory fires close→clear.
        int listPage = page(player.getUniqueId());
        confirming.put(player.getUniqueId(), badgeId);

        Inventory inv = Bukkit.createInventory(player, CONFIRM_SIZE, confirmTitle());
        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").build();
        for (int i = 0; i < CONFIRM_SIZE; i++) inv.setItem(i, filler);

        inv.setItem(CONFIRM_CANCEL_SLOT, new ItemBuilder(Material.RED_STAINED_GLASS_PANE)
                .name(plugin.getConfigManager().cosStr("badges.confirm-cancel")).build());
        inv.setItem(CONFIRM_INFO_SLOT, new ItemBuilder(Material.LIGHT_BLUE_DYE)
                .name(plugin.getConfigManager().cosStr("badges.confirm-info").replace("{badge}", t.name()))
                .lore(plugin.getConfigManager().cosStr("badges.confirm-price")
                        .replace("{price}", String.valueOf(t.price())))
                .build());
        inv.setItem(CONFIRM_ACCEPT_SLOT, new ItemBuilder(Material.LIME_STAINED_GLASS_PANE)
                .name(plugin.getConfigManager().cosStr("badges.confirm-accept")).build());

        player.openInventory(inv);
        state.put(player.getUniqueId(), new int[]{SCREEN_CONFIRM, listPage});
    }
}
