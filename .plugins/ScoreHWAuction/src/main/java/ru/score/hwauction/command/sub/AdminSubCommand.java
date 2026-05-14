package ru.score.hwauction.command.sub;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.AuctionItem;

import java.util.logging.Level;

public final class AdminSubCommand {

    private final ScoreHWAuction plugin;

    public AdminSubCommand(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public void execute(Player player, String[] args) {
        if (!player.hasPermission("scoreah.admin")) {
            player.sendMessage(plugin.getMessages().get("no-permission"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getMessages().get("admin-usage"));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "remove" -> handleRemove(player, args);
            case "clear"  -> handleClear(player, args);
            case "info"   -> handleInfo(player, args);
            default       -> player.sendMessage(plugin.getMessages().get("admin-usage"));
        }
    }

    private void handleRemove(Player admin, String[] args) {
        if (args.length < 3) {
            admin.sendMessage(plugin.getMessages().get("admin-usage")); return;
        }
        int id;
        try { id = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) {
            admin.sendMessage(plugin.getMessages().get("invalid-id")); return;
        }
        boolean ok = plugin.getListingService().cancel(admin, id);
        if (ok) admin.sendMessage(plugin.getMessages().get("admin-removed", "{id}", String.valueOf(id)));
        else    admin.sendMessage(plugin.getMessages().get("item-already-sold"));
    }

    private void handleClear(Player admin, String[] args) {
        if (args.length < 3) {
            // Clear all expired
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.getDb().markExpiredItems();
                    Bukkit.getScheduler().runTask(plugin,
                            () -> admin.sendMessage(plugin.getMessages().get("admin-cleared-expired")));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error clearing expired", e);
                }
            });
            return;
        }
        // /ah admin clear <player>
        String targetName = args[2];
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var items = plugin.getDb().loadItemsByPlayer(
                        target.getUniqueId(), ru.score.hwauction.model.AuctionStatus.ACTIVE);
                for (AuctionItem item : items) {
                    item.setStatus(ru.score.hwauction.model.AuctionStatus.CANCELLED);
                    plugin.getDb().updateItem(item);
                    plugin.getAuctionCache().remove(item.getId());
                }
                int count = items.size();
                Bukkit.getScheduler().runTask(plugin,
                        () -> admin.sendMessage(plugin.getMessages().get("admin-cleared-player",
                                "{player}", targetName, "{count}", String.valueOf(count))));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error clearing player listings", e);
            }
        });
    }

    private void handleInfo(Player admin, String[] args) {
        int total = plugin.getAuctionCache().size();
        admin.sendMessage(plugin.getMessages().get("admin-info-cache", "{count}", String.valueOf(total)));
    }
}
