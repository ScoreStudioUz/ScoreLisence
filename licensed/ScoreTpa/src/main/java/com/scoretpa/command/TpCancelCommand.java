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

public class TpCancelCommand implements CommandExecutor {

    private final ScoreTpa plugin;

    public TpCancelCommand(ScoreTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        ConfigManager cfg = plugin.getConfigManager();
        RequestManager rm = plugin.getRequestManager();

        TpaRequest request = rm.getActiveRequestBySender(player.getUniqueId());
        if (request == null) {
            MessageUtil.send(player, cfg.getMessage("no-pending-request"));
            return true;
        }

        Player target = Bukkit.getPlayer(request.getTarget());
        rm.removeRequest(request);

        MessageUtil.send(player, cfg.getMessage("request-cancelled",
                "%target%", target != null ? target.getName() : "?"));
        return true;
    }
}
