package ru.score.hwauction.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.gui.GuiTitle;
import ru.score.hwauction.util.ColorUtil;

public final class AuctionListener implements Listener {

    private final ScoreHWAuction plugin;
    private final GuiClickHandler clickHandler;

    public AuctionListener(ScoreHWAuction plugin) {
        this.plugin = plugin;
        this.clickHandler = new GuiClickHandler(plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() == null) return;
        if (e.getClickedInventory() == player.getInventory()) return;

        String rawTitle = ColorUtil.strip(e.getView().getTitle());
        if (!clickHandler.handles(rawTitle)) return;

        e.setCancelled(true);
        clickHandler.handle(e, player, rawTitle);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        String rawTitle = ColorUtil.strip(e.getView().getTitle());

        String mainTitle    = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_MAIN));
        String myTitle      = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_MY));
        String expiredTitle = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_EXPIRED));
        String bidTitle     = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_BID));
        String buyAmtTitle  = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_BUY_AMT));
        String buyConfTitle = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_BUY_CONFIRM));

        // When player presses ESC on a sub-menu → navigate back to main (1-tick delay)
        if (rawTitle.equals(myTitle) || rawTitle.equals(expiredTitle)
                || rawTitle.equals(bidTitle)) {
            plugin.getBuyAmountGui().removeSession(player);
            plugin.getBuyConfirmGui().removeSession(player);
            plugin.getBidViewGui().clearViewing(player);
            plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                    plugin.getMainGui().open(player), 1L);
            return;
        }

        // Buy amount → main
        if (rawTitle.equals(buyAmtTitle)) {
            plugin.getBuyAmountGui().removeSession(player);
            plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                    plugin.getMainGui().open(player), 1L);
            return;
        }

        // Confirm → back to buy-amount (or main if no session)
        if (rawTitle.equals(buyConfTitle)) {
            plugin.getBuyConfirmGui().removeSession(player);
            // Don't reopen — if they pressed ESC on confirm just go to main
            plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                    plugin.getMainGui().open(player), 1L);
            return;
        }

        // Main auction closed by ESC → do nothing (just let it close)
        // (No re-open needed)
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        plugin.getSessionManager().remove(player.getUniqueId());
        plugin.getBidViewGui().clearViewing(player);
        plugin.getBuyAmountGui().removeSession(player);
        plugin.getBuyConfirmGui().removeSession(player);
        plugin.getPendingConfirmManager().clear(player.getUniqueId());
        clickHandler.cleanupPlayer(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getSessionManager().getOrCreate(player);
            try {
                java.util.List<String> notifications = plugin.getDb().drainNotifications(player.getUniqueId());
                if (!notifications.isEmpty()) {
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        for (String msg : notifications) player.sendMessage(msg);
                    }, 40L);
                }
            } catch (Exception ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Failed to deliver pending notifications to " + player.getName(), ex);
            }
        });
    }
}
