package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Match;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Set;

/**
 * Section 3 — Command Restrictions
 *
 * During a match (PvP / Duel / Party FFA):
 *   Only /r, /msg, /leave (and admin /scorepvp) are allowed.
 *   All other commands are silently blocked with a message.
 *
 * Inside Vanilla Region:
 *   Only /kit save is allowed.
 *   All other commands are blocked.
 */
public class CommandBlockListener implements Listener {

    private final ScorePvP plugin;

    /** Commands allowed for fighters/spectators during an active match. */
    private static final Set<String> MATCH_ALLOWED = Set.of(
            "r", "msg", "w", "tell", "leave", "scorepvp", "spvp"
    );

    /** Commands allowed inside the vanilla (chest) region. */
    private static final Set<String> VANILLA_ALLOWED = Set.of(
            "kit", "scorepvp", "spvp"
    );

    public CommandBlockListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player player = e.getPlayer();
        String message = e.getMessage(); // "/command args..."

        // Extract base command (lowercase, no leading slash)
        String raw = message.startsWith("/") ? message.substring(1) : message;
        String base = raw.split(" ")[0].toLowerCase();
        // Strip plugin prefix (e.g. "minecraft:kill" → "kill")
        if (base.contains(":")) base = base.substring(base.indexOf(':') + 1);

        // ── Admin bypass ──────────────────────────────────────────────────────
        if (player.hasPermission("scorepvp.admin")) return;

        // ── Vanilla region: only /kit save allowed ────────────────────────────
        if (plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId())) {
            if (!VANILLA_ALLOWED.contains(base)) {
                e.setCancelled(true);
                player.sendMessage(plugin.getConfigManager().msg("command-blocked-vanilla"));
                return;
            }
            // /kit reset is strictly forbidden in vanilla region (section 6)
            if (base.equals("kit")) {
                String[] parts = raw.split(" ");
                if (parts.length >= 2 && parts[1].equalsIgnoreCase("reset")) {
                    e.setCancelled(true);
                    player.sendMessage(plugin.getConfigManager().msg("command-blocked-kit-reset"));
                }
            }
            return;
        }

        // ── Active match: only allowed commands ──────────────────────────────
        if (plugin.getMatchManager().isInMatch(player.getUniqueId())) {
            if (!MATCH_ALLOWED.contains(base)) {
                e.setCancelled(true);
                player.sendMessage(plugin.getConfigManager().msg("command-blocked-match"));
            }
        }
    }
}
