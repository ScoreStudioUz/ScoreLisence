package com.scoretpa.command;

import com.scoretpa.ScoreTpa;
import com.scoretpa.manager.ConfigManager;
import com.scoretpa.manager.RequestManager;
import com.scoretpa.model.TpaRequest;
import com.scoretpa.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TpDenyCommand implements CommandExecutor {

    private final ScoreTpa plugin;

    public TpDenyCommand(ScoreTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        ConfigManager cfg = plugin.getConfigManager();
        RequestManager rm = plugin.getRequestManager();
        TpaRequest request;

        if (args.length >= 1) {
            Player from = Bukkit.getPlayer(args[0]);
            if (from == null) {
                MessageUtil.send(player, cfg.getMessage("player-not-found"));
                return true;
            }
            request = rm.getRequestFrom(from.getUniqueId(), player.getUniqueId());
        } else {
            request = rm.getLatestRequestTo(player.getUniqueId());
        }

        if (request == null) {
            MessageUtil.send(player, cfg.getMessage("no-pending-request"));
            return true;
        }

        Player requestSender = Bukkit.getPlayer(request.getSender());
        rm.removeRequest(request);

        MessageUtil.send(player, cfg.getMessage("denied-request",
                "%player%", requestSender != null ? requestSender.getName() : "?"));
        if (requestSender != null) {
            MessageUtil.send(requestSender, cfg.getMessage("request-denied", "%target%", player.getName()));
        }
        return true;
    }
}
