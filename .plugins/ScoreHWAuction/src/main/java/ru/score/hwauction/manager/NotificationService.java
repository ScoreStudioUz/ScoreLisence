package ru.score.hwauction.manager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;

import java.util.UUID;

public final class NotificationService {

    private final ScoreHWAuction plugin;

    public NotificationService(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    /** Send message to an online player; silently drop if offline */
    public void send(UUID uuid, String message) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) p.sendMessage(message);
    }

    public void send(Player player, String message) {
        if (player != null && player.isOnline()) player.sendMessage(message);
    }

    /** Broadcast to all online players */
    public void broadcast(String message) {
        Bukkit.broadcastMessage(message);
    }

    /** Broadcast only if broadcast-purchase is enabled */
    public void broadcastPurchase(String message) {
        if (plugin.getPluginConfig().isBroadcastPurchase()) {
            broadcast(message);
        }
    }

    /** Send to a player and play error sound */
    public void sendError(Player player, String message) {
        send(player, message);
        plugin.getSoundService().playError(player);
    }
}
