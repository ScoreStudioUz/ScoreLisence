package com.scoretpa.command;

import com.scoretpa.ScoreTpa;
import com.scoretpa.manager.ConfigManager;
import com.scoretpa.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ScoreTpaCommand implements CommandExecutor {

    private final ScoreTpa plugin;

    public ScoreTpaCommand(ScoreTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(MessageUtil.color("&bScoreTpa &7v" + plugin.getDescription().getVersion()));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("scoretpa.admin")) {
                if (sender instanceof Player) {
                    MessageUtil.send((Player) sender, plugin.getConfigManager().getMessage("no-permission"));
                }
                return true;
            }
            plugin.reload();
            ConfigManager cfg = plugin.getConfigManager();
            if (sender instanceof Player) {
                MessageUtil.send((Player) sender, cfg.getMessage("config-reloaded"));
            } else {
                sender.sendMessage(MessageUtil.color(cfg.getPrefix() + cfg.getMessage("config-reloaded")));
            }
        }
        return true;
    }
}
