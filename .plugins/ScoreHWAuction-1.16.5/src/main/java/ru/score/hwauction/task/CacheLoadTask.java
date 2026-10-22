package ru.score.hwauction.task;

import org.bukkit.scheduler.BukkitRunnable;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.AuctionItem;

import java.util.List;
import java.util.logging.Level;

public final class CacheLoadTask extends BukkitRunnable {

    private final ScoreHWAuction plugin;

    public CacheLoadTask(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        try {
            // Mark expired before loading so we only cache truly active items
            plugin.getDb().markExpiredItems();

            List<AuctionItem> active = plugin.getDb().loadActiveItems();
            plugin.getAuctionCache().clear();
            plugin.getAuctionCache().putAll(active);

            plugin.getLogger().info("Auction cache loaded: " + active.size() + " active listing(s).");
            // Process auctions expired during downtime (pay sellers, deliver/queue items for winners)
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> plugin.getExpiryService().processRestartExpired());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load auction cache from database", e);
        }
    }

    /** Schedule this as a one-shot async task */
    public void schedule() {
        runTaskAsynchronously(plugin);
    }
}
