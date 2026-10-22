package com.scorepvp.command;

import com.scorepvp.ScorePvP;
import com.scorepvp.util.ColorUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /tags — opens the TierTag selector so a player can choose which mode's tier
 * is shown next to their name in the lobby / TAB.
 */
public class TierTagCommand implements CommandExecutor {

    private final ScorePvP plugin;

    public TierTagCommand(ScorePvP plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use /tags.");
            return true;
        }
        String msg = plugin.getConfigManager().rawMsg("tags.opening");
        if (msg != null && !msg.isEmpty() && !msg.equals("tags.opening")) {
            p.sendMessage(ColorUtil.color(msg));
        }
        plugin.getTierTagGUI().openSelect(p);
        return true;
    }
}
