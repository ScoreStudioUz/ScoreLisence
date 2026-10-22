package com.scorepvp.hats;

import com.scorepvp.ScorePvP;
import com.scorepvp.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The hats SHOP — a nishonlar-style two-screen GUI routed by tracked
 * screen-state (never by title, so shared titles can't collide).
 *
 * <p><b>List screen</b> (54 slots): the top control row (mockup slots 3/4/5)
 * shows the three category buttons; the currently-viewed category glows. The
 * remaining free slots (0..44, minus the category slots) hold that category's
 * hat icons, paginated. Each hat shows one of:
 * <ul>
 *   <li>equipped (selected + owned) → glowing + "O'rnatilgan"</li>
 *   <li>owned but inactive          → "O'rnatish uchun bosing"</li>
 *   <li>locked (not purchased)      → "Sotib olinmagan" + price</li>
 * </ul>
 *
 * <p><b>Confirm screen</b> (27 slots): cancel / info / accept — buying debits
 * silver and grants the hat.
 *
 * <p>State lives per-UUID; a single {@link HatGUI} instance is shared (created
 * once in {@link ScorePvP}). Every hat is freely removable in the lobby — this
 * shop only manages ownership + selection.
 */
public class HatGUI {

    public static final int LIST_SIZE    = 54;
    public static final int CONFIRM_SIZE = 27;

    // Confirm slots (match the nishonlar layout).
    public static final int CONFIRM_CANCEL_SLOT = 11;
    public static final int CONFIRM_INFO_SLOT   = 13;
    public static final int CONFIRM_ACCEPT_SLOT = 15;

    private static final int SCREEN_LIST = 1, SCREEN_CONFIRM = 2;

    private final ScorePvP plugin;
    /** uuid → [screen, page] while a hat menu is open. */
    private final Map<UUID, int[]> state = new ConcurrentHashMap<>();
    /** uuid → currently-viewed category id. */
    private final Map<UUID, String> viewingCategory = new ConcurrentHashMap<>();
    /** uuid → hat id being confirmed for purchase. */
    private final Map<UUID, String> confirming = new ConcurrentHashMap<>();
    /** uuid → map(list slot → hat id) for the currently-rendered page. */
    private final Map<UUID, Map<Integer, String>> slotToHat = new ConcurrentHashMap<>();
    /** uuid → map(category slot → category id) for the top button row. */
    private final Map<UUID, Map<Integer, String>> slotToCategory = new ConcurrentHashMap<>();

    public HatGUI(ScorePvP plugin) { this.plugin = plugin; }

    // ── State helpers ────────────────────────────────────────────────────────

    public boolean isListScreen(UUID u)    { int[] s = state.get(u); return s != null && s[0] == SCREEN_LIST; }
    public boolean isConfirmScreen(UUID u) { int[] s = state.get(u); return s != null && s[0] == SCREEN_CONFIRM; }
    public boolean isOpen(UUID u)          { return state.containsKey(u); }
    public int page(UUID u)                { int[] s = state.get(u); return s != null ? s[1] : 0; }
    public String viewingCategory(UUID u)  { return viewingCategory.get(u); }
    public String confirmingId(UUID u)     { return confirming.get(u); }
    public String hatIdAt(UUID u, int slot){ Map<Integer,String> m = slotToHat.get(u); return m == null ? null : m.get(slot); }
    public String categoryAt(UUID u, int slot){ Map<Integer,String> m = slotToCategory.get(u); return m == null ? null : m.get(slot); }

    public void clear(UUID u) {
        state.remove(u);
        slotToHat.remove(u);
        slotToCategory.remove(u);
        // viewingCategory + confirming intentionally survive a re-open within the
        // same interaction; they're reset when the menu is opened fresh.
    }

    // ── Fixed hat-icon slots (rows 2..5, cols 2..8) ──────────────────────────
    // 10..16, 19..25, 28..34, 37..43 — the user's layout. Category buttons live
    // in the top row (slots 3/4/5), separate from these content slots.
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private List<Integer> contentSlots() {
        List<Integer> out = new ArrayList<>(CONTENT_SLOTS.length);
        for (int s : CONTENT_SLOTS) out.add(s);
        return out;
    }

    private static boolean isContentSlot(int slot) {
        for (int s : CONTENT_SLOTS) if (s == slot) return true;
        return false;
    }

    // ── List screen ──────────────────────────────────────────────────────────

    public void open(Player player) {
        HatManager hats = plugin.getHatManager();
        String cat = viewingCategory.getOrDefault(player.getUniqueId(), hats.firstCategoryId());
        if (cat == null || hats.getCategory(cat) == null) cat = hats.firstCategoryId();
        if (cat == null) { player.closeInventory(); return; }
        viewingCategory.put(player.getUniqueId(), cat);
        openList(player, 0);
    }

    public void openList(Player player, int page) {
        HatManager hats = plugin.getHatManager();
        UUID uid = player.getUniqueId();
        String cat = viewingCategory.getOrDefault(uid, hats.firstCategoryId());
        if (cat == null) { player.closeInventory(); return; }

        List<HatDefinition> catHats = hats.hatsInCategory(cat);
        List<Integer> slots = contentSlots();
        int perPage = Math.max(1, slots.size());
        int maxPage = Math.max(0, (catHats.size() - 1) / perPage);
        if (page < 0) page = 0;
        if (page > maxPage) page = maxPage;

        Inventory inv = Bukkit.createInventory(player, LIST_SIZE, legacy(ColorUtil.color(hats.shopTitle())));
        // Fill every non-content slot with the filler so there are no bare holes;
        // category buttons + hat icons + nav are painted over it below.
        ItemStack filler = filler(hats.shopFiller());
        for (int i = 0; i < LIST_SIZE; i++) if (!isContentSlot(i)) inv.setItem(i, filler);

        // Category buttons (top row) — the viewed one glows.
        Map<Integer, String> catMap = new HashMap<>();
        for (HatManager.Category c : hats.getCategories()) {
            if (c.slot() < 0 || c.slot() >= LIST_SIZE) continue;
            boolean active = c.id().equals(cat);
            List<String> lore = new ArrayList<>();
            lore.add(hats.shopLorePrice().replace("{price}", String.valueOf(c.price())));
            inv.setItem(c.slot(), categoryIcon(hats, c, lore, active));
            catMap.put(c.slot(), c.id());
        }

        // Hat icons for this category/page.
        String selected = hats.getSelected(player);
        Map<Integer, String> hatMap = new HashMap<>();
        int start = page * perPage;
        for (int i = 0; i < perPage; i++) {
            int idx = start + i;
            if (idx >= catHats.size()) break;
            HatDefinition hat = catHats.get(idx);
            int slot = slots.get(i);
            boolean owned = hats.owns(player, hat);
            boolean equipped = hat.getId().equals(selected);
            inv.setItem(slot, hatIcon(hats, hat, owned, equipped));
            hatMap.put(slot, hat.getId());
        }

        // Nav + exit. Prev/next carry a resource-pack custom_model_data so the
        // vanilla arrow renders as the custom left/right arrow texture.
        inv.setItem(hats.shopExitSlot(), buildIcon(Material.BARRIER, hats.shopExitName(), null, false));
        if (page > 0)       inv.setItem(hats.shopPrevSlot(), navArrow(hats.shopPrevName(), hats.shopPrevCmd()));
        if (page < maxPage) inv.setItem(hats.shopNextSlot(), navArrow(hats.shopNextName(), hats.shopNextCmd()));

        // IMPORTANT: openInventory fires InventoryCloseEvent for the previously
        // open menu, whose handler calls clear(uid) and wipes slot maps. So we
        // open FIRST, then publish the per-UUID state — otherwise every click on
        // a freshly-opened page resolved to null (the "menu bug" the user hit).
        player.openInventory(inv);
        slotToCategory.put(uid, catMap);
        slotToHat.put(uid, hatMap);
        state.put(uid, new int[]{SCREEN_LIST, page});
    }

    public boolean hasNextPage(UUID uuid) {
        HatManager hats = plugin.getHatManager();
        String cat = viewingCategory.get(uuid);
        if (cat == null) return false;
        int perPage = Math.max(1, contentSlots().size());
        int maxPage = Math.max(0, (hats.hatsInCategory(cat).size() - 1) / perPage);
        int[] s = state.get(uuid);
        return s != null && s[1] < maxPage;
    }

    public void viewCategory(Player player, String categoryId) {
        if (plugin.getHatManager().getCategory(categoryId) == null) return;
        viewingCategory.put(player.getUniqueId(), categoryId);
        openList(player, 0);
    }

    private ItemStack hatIcon(HatManager hats, HatDefinition hat, boolean owned, boolean equipped) {
        // Base is the real hat model item (COAL + CMD) so the shop previews the
        // actual look; we layer lore + glow + hide-attributes on top.
        ItemStack item = hat.buildHatItem();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = new ArrayList<>();
        for (String l : hat.getLore())
            lore.add(legacy(ColorUtil.color(l)).decoration(TextDecoration.ITALIC, false));
        String stateLine = equipped ? hats.shopLoreEquipped()
                : owned ? hats.shopLoreOwned() : hats.shopLoreLocked();
        lore.add(legacy(ColorUtil.color(stateLine)).decoration(TextDecoration.ITALIC, false));
        if (!owned) {
            lore.add(legacy(ColorUtil.color(hats.shopLorePrice()
                    .replace("{price}", String.valueOf(hats.priceOf(hat)))))
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        hideAll(meta);
        item.setItemMeta(meta);
        if (equipped) addGlow(item);
        return item;
    }

    // ── Confirm screen ────────────────────────────────────────────────────────

    public void openConfirm(Player player, String hatId) {
        HatManager hats = plugin.getHatManager();
        HatDefinition hat = hats.getHat(hatId);
        if (hat == null) { openList(player, page(player.getUniqueId())); return; }
        UUID uid = player.getUniqueId();
        int listPage = page(uid); // capture before openInventory fires close→clear
        confirming.put(uid, hatId);

        Inventory inv = Bukkit.createInventory(player, CONFIRM_SIZE, legacy(ColorUtil.color(hats.shopConfirmTitle())));
        ItemStack filler = filler(hats.shopFiller());
        for (int i = 0; i < CONFIRM_SIZE; i++) inv.setItem(i, filler);

        inv.setItem(CONFIRM_CANCEL_SLOT, buildIcon(Material.RED_STAINED_GLASS_PANE, hats.shopConfirmCancel(), null, false));

        // Preview the actual hat model in the info slot.
        ItemStack info = hat.buildHatItem();
        ItemMeta im = info.getItemMeta();
        if (im != null) {
            im.displayName(legacy(ColorUtil.color(hats.shopConfirmInfo().replace("{hat}", hat.getDisplayName())))
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lc = new ArrayList<>();
            lc.add(legacy(ColorUtil.color(hats.shopConfirmPrice()
                    .replace("{price}", String.valueOf(hats.priceOf(hat))))).decoration(TextDecoration.ITALIC, false));
            im.lore(lc);
            hideAll(im);
            info.setItemMeta(im);
        }
        inv.setItem(CONFIRM_INFO_SLOT, info);

        inv.setItem(CONFIRM_ACCEPT_SLOT, buildIcon(Material.LIME_STAINED_GLASS_PANE, hats.shopConfirmAccept(), null, false));

        player.openInventory(inv);
        state.put(uid, new int[]{SCREEN_CONFIRM, listPage});
    }

    // ── Item helpers ──────────────────────────────────────────────────────────

    /**
     * The category button: shows its preview-hat's model when set (so players
     * see an actual hat, not iron/gold/diamond), else the fallback icon
     * material. The viewed category glows.
     */
    private ItemStack categoryIcon(HatManager hats, HatManager.Category c, List<String> lore, boolean active) {
        HatDefinition preview = hats.getHat(c.previewHat());
        if (preview == null) return buildIcon(c.icon(), c.displayName(), lore, active);
        ItemStack item = preview.buildHatItem();
        ItemMeta m = item.getItemMeta();
        if (m != null) {
            m.displayName(legacy(ColorUtil.color(c.displayName())).decoration(TextDecoration.ITALIC, false));
            List<Component> lc = new ArrayList<>();
            for (String l : lore) lc.add(legacy(ColorUtil.color(l)).decoration(TextDecoration.ITALIC, false));
            m.lore(lc);
            hideAll(m);
            item.setItemMeta(m);
        }
        if (active) addGlow(item);
        return item;
    }

    /** A prev/next arrow button, optionally carrying a custom_model_data. */
    private ItemStack navArrow(String name, int cmd) {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(legacy(ColorUtil.color(name)).decoration(TextDecoration.ITALIC, false));
            if (cmd > 0) m.setCustomModelData(cmd);
            hideAll(m);
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack buildIcon(Material mat, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(legacy(ColorUtil.color(name)).decoration(TextDecoration.ITALIC, false));
            if (lore != null && !lore.isEmpty()) {
                List<Component> lc = new ArrayList<>();
                for (String l : lore) lc.add(legacy(ColorUtil.color(l)).decoration(TextDecoration.ITALIC, false));
                m.lore(lc);
            }
            hideAll(m);
            it.setItemMeta(m);
        }
        if (glow) addGlow(it);
        return it;
    }

    private ItemStack filler(String matName) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) { m.displayName(Component.empty()); hideAll(m); it.setItemMeta(m); }
        return it;
    }

    private void hideAll(ItemMeta meta) {
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
    }

    private void addGlow(ItemStack item) {
        ItemMeta m = item.getItemMeta();
        if (m == null) return;
        m.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
        m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(m);
    }

    private Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
