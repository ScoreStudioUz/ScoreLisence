package com.scoretpa.command;

import com.scoretpa.ScoreTpa;
import com.scoretpa.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TpaCommand implements CommandExecutor, TabCompleter {

    private final ScoreTpa plugin;

    public TpaCommand(ScoreTpa plugin) {
        this.plugin = plugin;
        plugin.getCommand("tpa").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (args.length < 1) {
            MessageUtil.send(player, plugin.getConfigManager().getMessage("usage-tpa"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            MessageUtil.send(player, plugin.getConfigManager().getMessage("player-not-found"));
            return true;
        }

        plugin.getRequestManager().sendRequest(player, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> completions = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) {
                    completions.add(p.getName());
                }
            }
            return completions;
        }
        return Collections.emptyList();
    }
}
