package ru.score.hwauction.command.sub;

import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.PlayerSession;

import java.util.Arrays;

public final class SearchSubCommand {

    private final ScoreHWAuction plugin;

    public SearchSubCommand(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public void execute(Player player, String[] args) {
        if (!player.hasPermission("scoreah.search")) {
            player.sendMessage(plugin.getMessages().get("no-permission"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getMessages().get("search-usage"));
            return;
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        PlayerSession session = plugin.getSessionManager().getOrCreate(player);
        session.setSearchQuery(query);
        session.setPlayerFilter(null);
        player.sendMessage(plugin.getMessages().get("search-applied", "{query}", query));
        plugin.getMainGui().open(player);
    }
}
