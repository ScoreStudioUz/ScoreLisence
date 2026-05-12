package ru.score.hwauction.gui.buyamount;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.gui.GuiUtil;
import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.util.MathUtil;
import ru.score.hwauction.util.MoneyUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Purchase confirmation GUI — 27 slots (3 rows).
 *
 * Layout:
 * [L][L][L][G][G][G][R][R][R]   row 0
 * [L][L][L][G][I][G][R][R][R]   row 1  (I = item at slot 13)
 * [L][L][L][G][G][G][R][R][R]   row 2
 *
 * L = LIME_STAINED_GLASS_PANE  (slots: 0,1,2, 9,10,11, 18,19,20) → confirm
 * G = GRAY_STAINED_GLASS_PANE  (slots: 3,4,5, 12,14, 21,22,23)   → decoration
 * I = item                     (slot 13)
 * R = RED_STAINED_GLASS_PANE   (slots: 6,7,8, 15,16,17, 24,25,26) → cancel
 */
public final class BuyConfirmGui {

    public record ConfirmSession(int itemId, int amount) {}

    private final ScoreHWAuction plugin;
    private final ConcurrentHashMap<UUID, ConfirmSession> sessions = new ConcurrentHashMap<>();

    // Slot sets
    private static final int[] LIME_SLOTS = {0,1,2, 9,10,11, 18,19,20};
    private static final int[] GRAY_SLOTS = {3,4,5, 12,14, 21,22,23};
    private static final int[] RED_SLOTS  = {6,7,8, 15,16,17, 24,25,26};
    private static final int   ITEM_SLOT  = 13;

    public BuyConfirmGui(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, AuctionItem item, int amount) {
        sessions.put(player.getUniqueId(), new ConfirmSession(item.getId(), amount));
        render(player, item, amount);
    }

    private void render(Player player, AuctionItem item, int amount) {
        Inventory inv = Bukkit.createInventory(null, 27,
                plugin.getMessages().raw("gui.buy-confirm-title"));

        double totalPrice = MathUtil.round(item.getPricePerUnit() * amount, 2);

        // LIME — confirm
        List<String> yesLore = List.of(plugin.getMessages().raw("gui.buy-confirm-yes-lore",
                "{amount}", String.valueOf(amount),
                "{price}",  MoneyUtil.format(totalPrice)));
        ItemStack yes = GuiUtil.build(Material.LIME_STAINED_GLASS_PANE,
                plugin.getMessages().raw("gui.buy-confirm-yes"), yesLore);
        for (int s : LIME_SLOTS) inv.setItem(s, yes);

        // GRAY — decoration
        ItemStack gray = GuiUtil.build(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int s : GRAY_SLOTS) inv.setItem(s, gray);

        // ITEM — centre
        inv.setItem(ITEM_SLOT, buildCentreItem(item, amount, totalPrice));

        // RED — cancel
        List<String> noLore = List.of(plugin.getMessages().raw("gui.buy-confirm-no-lore"));
        ItemStack no = GuiUtil.build(Material.RED_STAINED_GLASS_PANE,
                plugin.getMessages().raw("gui.buy-confirm-no"), noLore);
        for (int s : RED_SLOTS) inv.setItem(s, no);

        player.openInventory(inv);
    }

    private ItemStack buildCentreItem(AuctionItem item, int amount, double totalPrice) {
        ItemStack display = item.getItemStack().clone();
        display.setAmount(amount);

        ItemMeta meta = display.getItemMeta();
        if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(display.getType());
        if (meta == null) return display;

        List<String> lore = new ArrayList<>();
        if (meta.hasLore()) lore.addAll(meta.getLore());
        lore.add("");
        lore.add(plugin.getMessages().raw("gui.buy-confirm",
                "{amount}", String.valueOf(amount),
                "{price}",  MoneyUtil.format(totalPrice)));
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    /** Lime slots → confirm */
    public boolean isYes(int slot) {
        for (int s : LIME_SLOTS) if (s == slot) return true;
        return false;
    }

    /** Red slots → cancel */
    public boolean isNo(int slot) {
        for (int s : RED_SLOTS) if (s == slot) return true;
        return false;
    }

    public ConfirmSession getSession(Player player)  { return sessions.get(player.getUniqueId()); }
    public void removeSession(Player player)         { sessions.remove(player.getUniqueId()); }
}
