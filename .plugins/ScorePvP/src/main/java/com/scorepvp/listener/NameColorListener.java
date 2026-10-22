package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.gui.NameColorGUI;
import com.scorepvp.manager.NameStyleManager.ColorEntry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;

/**
 * Routes clicks in the name-color picker (standart + gradient share this GUI).
 * Selecting a color applies it instantly everywhere (chat/TAB read
 * %scorepvp_name% live). Routing is by tracked open-state, not title, so the
 * two variants never collide even if an admin gives them the same title.
 */
public class NameColorListener implements Listener {

    private final ScorePvP plugin;

    public NameColorListener(ScorePvP plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        NameColorGUI gui = plugin.getNameColorGUI();
        if (!gui.isOpen(p.getUniqueId())) return;

        e.setCancelled(true);
        if (e.getClickedInventory() == null
                || e.getClickedInventory().getType() == InventoryType.PLAYER) return;

        int slot = e.getRawSlot();
        boolean premium = gui.isPremiumScreen(p.getUniqueId());
        int page = gui.page(p.getUniqueId());

        switch (slot) {
            case NameColorGUI.BACK_SLOT -> {
                if (page > 0) { gui.open(p, premium, page - 1); }
                else { plugin.getCosmeticsGUI().openMain(p); }
                return;
            }
            case NameColorGUI.NEXT_SLOT -> {
                if (gui.hasNextPage(p.getUniqueId())) gui.open(p, premium, page + 1);
                return;
            }
            case NameColorGUI.RESET_SLOT -> {
                plugin.getNameStyleManager().setColor(p.getUniqueId(), null);
                plugin.getNameTagProvider().applyLobbyNametag(p);
                tell(p, "namecolor.reset-done");
                gui.open(p, premium, page);
                return;
            }
            case NameColorGUI.EXIT_SLOT -> { p.closeInventory(); return; }
            default -> { /* swatch */ }
        }

        String id = gui.colorIdAt(p, slot);
        if (id == null) return;
        ColorEntry c = plugin.getNameStyleManager().getColor(id);
        if (c == null) return;
        if (!plugin.getNameStyleManager().canUse(p, c)) {
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            tell(p, "namecolor.locked-msg");
            return;
        }
        plugin.getNameStyleManager().setColor(p.getUniqueId(), id);
        plugin.getNameTagProvider().applyLobbyNametag(p);
        tell(p, "namecolor.applied");
        gui.open(p, premium, page); // refresh to show the new active highlight
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (plugin.getNameColorGUI().isOpen(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        plugin.getNameColorGUI().close(p.getUniqueId());
    }

    private void tell(Player p, String key) {
        String prefix = plugin.getConfigManager().cosStr("messages.prefix");
        String body   = plugin.getConfigManager().cosStr(key);
        if (body == null || body.isEmpty()) return;
        p.sendMessage(com.scorepvp.util.ColorUtil.color(prefix + body));
    }
}
