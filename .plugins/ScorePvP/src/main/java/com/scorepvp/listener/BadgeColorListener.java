package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.gui.BadgeColorGUI;
import com.scorepvp.manager.NameStyleManager;
import com.scorepvp.manager.NameStyleManager.ColorEntry;
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
 * Routes the badge-color picker. Selecting a color recolors the player's chosen
 * badge glyph (applied live via NameStyleManager.tagPrefix); reset clears it back
 * to the badge's own color.
 */
public class BadgeColorListener implements Listener {

    private final ScorePvP plugin;

    public BadgeColorListener(ScorePvP plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        BadgeColorGUI gui = plugin.getBadgeColorGUI();
        if (!gui.isOpen(p.getUniqueId())) return;

        e.setCancelled(true);
        if (e.getClickedInventory() == null
                || e.getClickedInventory().getType() == InventoryType.PLAYER) return;

        int slot = e.getRawSlot();
        int page = gui.page(p.getUniqueId());
        NameStyleManager ns = plugin.getNameStyleManager();

        switch (slot) {
            case BadgeColorGUI.EXIT_SLOT -> { plugin.getBadgeGUI().openList(p, 0); return; }
            case BadgeColorGUI.BACK_SLOT -> { if (page > 0) gui.open(p, page - 1); return; }
            case BadgeColorGUI.NEXT_SLOT -> { if (gui.hasNextPage(p.getUniqueId())) gui.open(p, page + 1); return; }
            case BadgeColorGUI.RESET_SLOT -> {
                ns.setBadgeColor(p.getUniqueId(), null);
                plugin.getNameTagProvider().applyLobbyNametag(p);
                tell(p, "badgecolor.reset-done");
                gui.open(p, page);
                return;
            }
            default -> { /* a color swatch */ }
        }

        String id = gui.colorIdAt(p, slot);
        if (id == null) return;
        ColorEntry c = ns.getColor(id);
        if (c == null) return;
        if (!ns.canUse(p, c)) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            tell(p, "badgecolor.locked-msg");
            return;
        }
        ns.setBadgeColor(p.getUniqueId(), id);
        plugin.getNameTagProvider().applyLobbyNametag(p);
        tell(p, "badgecolor.selected");
        gui.open(p, page);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (plugin.getBadgeColorGUI().isOpen(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        plugin.getBadgeColorGUI().close(p.getUniqueId());
    }

    private void tell(Player p, String key) {
        String prefix = plugin.getConfigManager().cosStr("messages.prefix");
        String body   = plugin.getConfigManager().cosStr(key);
        if (body == null || body.isEmpty()) return;
        p.sendMessage(com.scorepvp.util.ColorUtil.color(prefix + body));
    }
}
