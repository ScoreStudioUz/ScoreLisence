package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Match;
import com.scorepvp.model.MatchPlayer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * Hides spectator chat from the fighters of the duel they're watching.
 *
 * <p>When a player who is spectating a match types in chat, the two fighters of
 * THAT match are removed from the message's viewer set. Everyone else — other
 * spectators and the rest of the server — still sees the message normally, and
 * the chat-format plugin (ChatEx) formats it as usual; we only trim the
 * audience, never the message itself.
 *
 * <p>Runs at LOW priority so it executes before ChatEx's HIGH/HIGHEST formatter
 * and before {@link PartyChatListener} (HIGHEST) — viewer removal is order
 * independent, but running early guarantees the trimmed audience is what later
 * handlers observe. Fighters' own messages are untouched: everyone sees them.
 */
public class SpectatorChatListener implements Listener {

    private final ScorePvP plugin;

    public SpectatorChatListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("spectator-chat.enabled", true)) return;

        Player sender = e.getPlayer();
        if (!plugin.getMatchManager().isSpectating(sender.getUniqueId())) return;

        Match match = plugin.getMatchManager().getMatch(sender.getUniqueId());
        if (match == null) return;

        UUID f1 = match.getFighter1() != null ? match.getFighter1().getUuid() : null;
        UUID f2 = match.getFighter2() != null ? match.getFighter2().getUuid() : null;

        // FFA matches expose fighters via the spectator/fighter split too; remove
        // every fighter of the match, not just the two duel slots.
        e.viewers().removeIf(audience -> isFighterOf(audience, match, f1, f2));
    }

    private boolean isFighterOf(Audience audience, Match match, UUID f1, UUID f2) {
        if (!(audience instanceof Player viewer)) return false;
        UUID id = viewer.getUniqueId();
        if (id.equals(f1) || id.equals(f2)) return true;
        // Covers FFA where fighters aren't in the two duel slots.
        return match.isFighter(id);
    }
}
