package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PlayerSettings;
import com.scorepvp.model.PlayerSettings.Visibility;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MsgManager {

    private final ScorePvP plugin;

    /** uuid → UUID of last conversation partner */
    private final Map<UUID, UUID> lastPartner = new ConcurrentHashMap<>();

    public MsgManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    public void send(Player sender, Player target, String message) {
        ConfigManager cfg = plugin.getConfigManager();

        // Check target's DM settings
        PlayerSettings ts = plugin.getSettingsManager().get(target.getUniqueId());
        if (ts.getDirectMessages() == Visibility.ONLY_FRIENDS) {
            if (!plugin.getFriendsManager().areFriends(sender.getUniqueId(), target.getUniqueId())) {
                sender.sendMessage(cfg.msg("msg.blocked", "{player}", target.getName()));
                return;
            }
        }

        // Section 7: apply color AFTER all replacements so message body is colored
        String rawFmt = cfg.getLang().getString("messages.dm-format",
                "&8[&bЛС&8] &e{sender} &7→ &e{target}&7: &f{message}");
        String format = com.scorepvp.util.ColorUtil.color(
                rawFmt.replace("{sender}", sender.getName())
                      .replace("{target}", target.getName())
                      .replace("{message}", message));

        sender.sendMessage(format);
        target.sendMessage(format);

        // Record conversation partners
        lastPartner.put(sender.getUniqueId(), target.getUniqueId());
        lastPartner.put(target.getUniqueId(), sender.getUniqueId());
    }

    // ── Reply ─────────────────────────────────────────────────────────────────

    public void reply(Player sender, String message) {
        UUID partnerUuid = lastPartner.get(sender.getUniqueId());
        if (partnerUuid == null) {
            sender.sendMessage(plugin.getConfigManager().msg("msg.no-partner"));
            return;
        }
        Player partner = Bukkit.getPlayer(partnerUuid);
        if (partner == null || !partner.isOnline()) {
            sender.sendMessage(plugin.getConfigManager().msg("msg.partner-offline"));
            return;
        }
        send(sender, partner, message);
    }

    public void clearPartner(UUID uuid) { lastPartner.remove(uuid); }
}
