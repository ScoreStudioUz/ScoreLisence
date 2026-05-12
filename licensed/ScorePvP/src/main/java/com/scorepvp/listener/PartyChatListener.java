package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Intercepts chat and routes messages through the party chat system.
 * Uses Paper's AsyncChatEvent (replaces deprecated AsyncPlayerChatEvent in 1.21).
 */
public class PartyChatListener implements Listener {

    private final ScorePvP plugin;

    public PartyChatListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        // Extract plain-text message from the Adventure Component
        String message = PlainTextComponentSerializer.plainText().serialize(e.message());

        boolean handled = plugin.getPartyManager().handleChat(e.getPlayer(), message);
        if (handled) {
            e.setCancelled(true);
        }
    }
}
