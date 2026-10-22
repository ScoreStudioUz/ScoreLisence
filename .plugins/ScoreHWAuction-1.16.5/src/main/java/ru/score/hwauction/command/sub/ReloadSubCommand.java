package ru.score.hwauction.command.sub;

import org.bukkit.command.CommandSender;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.util.MoneyUtil;
import ru.score.hwauction.util.TimeUtil;

public final class ReloadSubCommand {

    private final ScoreHWAuction plugin;

    public ReloadSubCommand(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("scoreah.admin.reload")) {
            sender.sendMessage(plugin.getMessages().get("no-permission"));
            return;
        }
        plugin.getPluginConfig().reload();
        plugin.getMessages().reload();
        plugin.getCategoryRegistry().reload();
        plugin.getBlacklistRegistry().reload();
        plugin.getTemplateRegistry().reload();
        plugin.getRuNameProvider().load();
        MoneyUtil.setCurrencySymbol(plugin.getPluginConfig().getCurrencySymbol());
        TimeUtil.reload(plugin.getMessages());
        plugin.getCacheLoadTask().schedule();
        sender.sendMessage(plugin.getMessages().get("reload-success"));
    }
}
