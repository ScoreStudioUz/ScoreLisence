package ru.score.hwauction.command.sub;

import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;

public final class HelpSubCommand {

    private final ScoreHWAuction plugin;

    public HelpSubCommand(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public void execute(Player player) {
        for (String line : plugin.getMessages().list("help-lines")) {
            player.sendMessage(line);
        }
    }
}
