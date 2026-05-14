package com.scoretpa.command;

import com.scoretpa.ScoreTpa;
import com.scoretpa.manager.ConfigManager;
import com.scoretpa.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TpToggleCommand implements CommandExecutor {

    private final ScoreTpa plugin;

    public TpToggleCommand(ScoreTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        ConfigManager cfg = plugin.getConfigManager();
        boolean enabled = plugin.getRequestManager().toggle(player.getUniqueId());

        if (enabled) {
            MessageUtil.send(player, cfg.getMessage("tptoggle-enabled"));
        } else {
            MessageUtil.send(player, cfg.getMessage("tptoggle-disabled"));
        }
        return true;
    }
}
