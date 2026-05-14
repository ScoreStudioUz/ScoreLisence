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
 * Buy-by-unit selection GUI — 5 slots (1 row / воронка):
 *
 * [0]=RED -10  [1]=RED -1  [2]=ITEM×N  [3]=LIME +1  [4]=LIME +10
 *
 * Click item (slot 2) → opens confirmation GUI.
 */
public final class BuyAmountGui {

    public record BuySession(int itemId, int amount) {
        public BuySession withAmount(int newAmt) { return new BuySession(itemId, newAmt); }
    }

    private final ScoreHWAuction plugin;
    private final ConcurrentHashMap<UUID, BuySession> sessions = new ConcurrentHashMap<>();

    public BuyAmountGui(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, AuctionItem item) {
        BuySession s = sessions.computeIfAbsent(
                player.getUniqueId(), u -> new BuySession(item.getId(), 1));
        int amt = MathUtil.clamp(s.amount(), 1, item.getAmount());
        sessions.put(player.getUniqueId(), s.withAmount(amt));
        render(player, item, amt);
    }

    public void render(Player player, AuctionItem item, int amt) {
        // 5-slot hopper inventory
        Inventory inv = Bukkit.createInventory(null, 5,
                plugin.getMessages().raw("gui.buy-amount-title"));

        boolean canDec10 = amt > 10;
        boolean canDec1  = amt > 1;
        boolean canInc1  = amt < item.getAmount();
        boolean canInc10 = amt + 10 <= item.getAmount();

        // Slot 0 — red -10
        inv.setItem(0, GuiUtil.build(
                canDec10 ? Material.RED_CONCRETE : Material.RED_STAINED_GLASS_PANE,
                plugin.getMessages().raw("gui.buy-decrease-10")));

        // Slot 1 — red -1
        inv.setItem(1, GuiUtil.build(
                canDec1 ? Material.RED_CONCRETE : Material.RED_STAINED_GLASS_PANE,
                plugin.getMessages().raw("gui.buy-decrease-1")));

        // Slot 2 — actual item with count badge
        inv.setItem(2, buildItemSlot(item, amt));

        // Slot 3 — lime +1
        inv.setItem(3, GuiUtil.build(
                canInc1 ? Material.LIME_CONCRETE : Material.LIME_STAINED_GLASS_PANE,
                plugin.getMessages().raw("gui.buy-increase-1")));

        // Slot 4 — lime +10
        inv.setItem(4, GuiUtil.build(
                canInc10 ? Material.LIME_CONCRETE : Material.LIME_STAINED_GLASS_PANE,
                plugin.getMessages().raw("gui.buy-increase-10")));

        player.openInventory(inv);
    }

    private ItemStack buildItemSlot(AuctionItem item, int amt) {
        ItemStack display = item.getItemStack().clone();
        display.setAmount(amt);

        ItemMeta meta = display.getItemMeta();
        if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(display.getType());
        if (meta == null) return display;

        double totalPrice = MathUtil.round(item.getPricePerUnit() * amt, 2);
        List<String> lore = new ArrayList<>();
        if (meta.hasLore()) lore.addAll(meta.getLore());
        lore.add("");
        lore.add(plugin.getMessages().raw("gui.buy-amount-info", "{amount}", String.valueOf(amt)));
        lore.add(plugin.getMessages().raw("gui.buy-confirm",
                "{amount}", String.valueOf(amt),
                "{price}",  MoneyUtil.format(totalPrice)));
        lore.add("");
        lore.add(plugin.getMessages().raw("gui.buy-item-click-hint"));
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    public void adjust(Player player, AuctionItem item, int delta) {
        BuySession s = sessions.get(player.getUniqueId());
        if (s == null) return;
        int newAmt = MathUtil.clamp(s.amount() + delta, 1, item.getAmount());
        sessions.put(player.getUniqueId(), s.withAmount(newAmt));
        render(player, item, newAmt);
    }

    public BuySession getSession(Player player)  { return sessions.get(player.getUniqueId()); }
    public void removeSession(Player player)     { sessions.remove(player.getUniqueId()); }
}
