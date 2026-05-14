package ru.score.hwauction.command.sub;

import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.PlayerSession;

public final class PlayerSubCommand {

    private final ScoreHWAuction plugin;

    public PlayerSubCommand(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public void execute(Player player, String[] args) {
        if (!player.hasPermission("scoreah.player")) {
            player.sendMessage(plugin.getMessages().get("no-permission"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getMessages().get("player-usage"));
            return;
        }
        String target = args[1].trim();
        PlayerSession session = plugin.getSessionManager().getOrCreate(player);
        session.setPlayerFilter(target);
        session.setSearchQuery(null);
        player.sendMessage(plugin.getMessages().get("player-filter-applied", "{player}", target));
        plugin.getMainGui().open(player);
    }
}
