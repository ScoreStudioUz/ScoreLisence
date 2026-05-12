package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.MenuManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles admin menu editor sessions.
 *
 * When an admin opens a menu editor, all inventory interactions are allowed.
 * On close, the inventory contents are saved back to the menu YAML file.
 */
public class MenuEditorListener implements Listener {

    private final ScorePvP plugin;
    private final Map<UUID, String> editingSessions = new ConcurrentHashMap<>();

    public MenuEditorListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    public void startEditing(UUID uuid, String menuName) {
        editingSessions.put(uuid, menuName);
    }

    public boolean isEditing(UUID uuid) {
        return editingSessions.containsKey(uuid);
    }

    // ── Close — auto-save ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        String menuName = editingSessions.remove(player.getUniqueId());
        if (menuName == null) return;

        // Save inventory contents to YML
        plugin.getMenuManager().saveFromInventory(menuName, e.getInventory());
        player.sendMessage(plugin.getConfigManager().msg("admin.menu-saved", "{menu}", menuName));
    }
}
