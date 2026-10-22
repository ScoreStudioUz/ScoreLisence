package ru.score.hwauction.command.sub;

import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.PlayerSession;

public final class SoundSubCommand {

    private final ScoreHWAuction plugin;

    public SoundSubCommand(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public void execute(Player player, String[] args) {
        if (!player.hasPermission("scoreah.sound")) {
            player.sendMessage(plugin.getMessages().get("no-permission"));
            return;
        }
        PlayerSession session = plugin.getSessionManager().getOrCreate(player);
        boolean newState = !session.isSoundEnabled();
        session.setSoundEnabled(newState);

        // Persist async
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try { plugin.getDb().saveSoundPreference(player.getUniqueId(), newState); }
            catch (Exception ignored) {}
        });

        String key = newState ? "sound-enabled" : "sound-disabled";
        player.sendMessage(plugin.getMessages().get(key));
    }
}
