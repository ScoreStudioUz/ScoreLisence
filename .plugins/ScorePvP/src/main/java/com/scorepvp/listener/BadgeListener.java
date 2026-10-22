package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.gui.BadgeGUI;
import com.scorepvp.manager.NameStyleManager;
import com.scorepvp.manager.NameStyleManager.TagEntry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;

/**
 * Routes the badges list + purchase-confirm screens. Buying debits silver and
 * grants the badge; selecting an owned badge applies it everywhere instantly
 * (chat/TAB read %scorepvp_name% live).
 */
public class BadgeListener implements Listener {

    private final ScorePvP plugin;

    public BadgeListener(ScorePvP plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        BadgeGUI gui = plugin.getBadgeGUI();
        boolean list    = gui.isListScreen(p.getUniqueId());
        boolean confirm = gui.isConfirmScreen(p.getUniqueId());
        if (!list && !confirm) return;

        e.setCancelled(true);
        if (e.getClickedInventory() == null
                || e.getClickedInventory().getType() == InventoryType.PLAYER) return;

        int slot = e.getRawSlot();
        if (list) handleList(p, slot);
        else      handleConfirm(p, slot);
    }

    private void handleList(Player p, int slot) {
        BadgeGUI gui = plugin.getBadgeGUI();
        NameStyleManager ns = plugin.getNameStyleManager();
        int page = gui.page(p.getUniqueId());

        switch (slot) {
            case BadgeGUI.LIST_EXIT_SLOT -> { p.closeInventory(); return; }
            case BadgeGUI.LIST_NEXT_SLOT -> {
                if (gui.hasNextPage(p.getUniqueId())) gui.openList(p, page + 1);
                return;
            }
            case BadgeGUI.LIST_RESET_SLOT -> {
                ns.setTag(p.getUniqueId(), null);
                plugin.getNameTagProvider().applyLobbyNametag(p);
                tell(p, "badges.reset-done");
                gui.openList(p, page);
                return;
            }
            case BadgeGUI.LIST_COLORPICK_SLOT -> {
                if (ns.ownedBadgeCount(p) >= ns.getNamecolorUnlockAfter()) {
                    plugin.getBadgeColorGUI().open(p, 0);
                } else {
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    tell(p, "badges.colorpick-locked-msg");
                }
                return;
            }
            default -> { /* a badge slot */ }
        }

        String id = gui.badgeIdAt(p, slot);
        if (id == null) return;
        TagEntry t = ns.getTag(id);
        if (t == null) return;

        if (ns.ownsBadge(p, t)) {
            // Toggle: if it's already active, deselect; else select.
            var st = plugin.getSettingsManager().get(p.getUniqueId());
            String active = st != null ? st.getNameTag() : null;
            if (id.equals(active)) {
                ns.setTag(p.getUniqueId(), null);
                tell(p, "badges.deselected");
            } else {
                ns.setTag(p.getUniqueId(), id);
                tell(p, "badges.selected");
            }
            plugin.getNameTagProvider().applyLobbyNametag(p);
            gui.openList(p, page);
        } else {
            // Not owned → purchase confirm.
            gui.openConfirm(p, id);
        }
    }

    private void handleConfirm(Player p, int slot) {
        BadgeGUI gui = plugin.getBadgeGUI();
        NameStyleManager ns = plugin.getNameStyleManager();
        String id = gui.confirmingId(p.getUniqueId());
        int page = gui.page(p.getUniqueId());

        if (slot == BadgeGUI.CONFIRM_CANCEL_SLOT) { gui.openList(p, page); return; }
        if (slot != BadgeGUI.CONFIRM_ACCEPT_SLOT) return;

        TagEntry t = id != null ? ns.getTag(id) : null;
        if (t == null) { gui.openList(p, page); return; }
        if (ns.ownsBadge(p, t)) { gui.openList(p, page); return; } // already owned

        int cost = t.price();
        if (!plugin.getSilverManager().trySpend(p.getUniqueId(), cost)) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            tellPh(p, "badges.not-enough", "{price}", String.valueOf(cost));
            gui.openList(p, page);
            return;
        }
        ns.grantBadge(p.getUniqueId(), id);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
        tellPh(p, "badges.bought", "{badge}", t.name());
        gui.openList(p, page);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (plugin.getBadgeGUI().isOpen(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        plugin.getBadgeGUI().clear(p.getUniqueId());
    }

    private void tell(Player p, String key) {
        String prefix = plugin.getConfigManager().cosStr("messages.prefix");
        String body   = plugin.getConfigManager().cosStr(key);
        if (body == null || body.isEmpty()) return;
        p.sendMessage(com.scorepvp.util.ColorUtil.color(prefix + body));
    }
    private void tellPh(Player p, String key, String ph, String v) {
        String prefix = plugin.getConfigManager().cosStr("messages.prefix");
        String body   = plugin.getConfigManager().cosStr(key).replace(ph, v);
        p.sendMessage(com.scorepvp.util.ColorUtil.color(prefix + body));
    }
}
