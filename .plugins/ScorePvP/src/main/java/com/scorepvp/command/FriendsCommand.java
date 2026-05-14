package com.scorepvp.command;

import com.scorepvp.ScorePvP;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

public class FriendsCommand implements CommandExecutor, TabCompleter {

    private final ScorePvP plugin;

    public FriendsCommand(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(plugin.getConfigManager().rawMsg("players-only")); return true; }

        if (args.length == 0) { sendHelp(player); return true; }

        switch (args[0].toLowerCase()) {
            case "add" -> {
                if (args.length < 2) { player.sendMessage(plugin.getConfigManager()
                        .msg("invalid-usage", "{usage}", "/friends add <ник>")); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { player.sendMessage(plugin.getConfigManager()
                        .msg("player-not-found", "{player}", args[1])); return true; }
                plugin.getFriendsManager().sendRequest(player, target);
            }
            case "remove" -> {
                if (args.length < 2) { player.sendMessage(plugin.getConfigManager()
                        .msg("invalid-usage", "{usage}", "/friends remove <ник>")); return true; }
                plugin.getFriendsManager().removeFriend(player, args[1]);
            }
            case "list" -> plugin.getFriendsManager().showList(player);
            case "accept" -> {
                if (args.length < 2) { player.sendMessage(plugin.getConfigManager()
                        .msg("invalid-usage", "{usage}", "/friends accept <ник>")); return true; }
                plugin.getFriendsManager().acceptRequest(player, args[1]);
            }
            case "deny" -> {
                if (args.length < 2) { player.sendMessage(plugin.getConfigManager()
                        .msg("invalid-usage", "{usage}", "/friends deny <ник>")); return true; }
                plugin.getFriendsManager().denyRequest(player, args[1]);
            }
            case "requests" -> plugin.getFriendsManager().showRequests(player);
            default -> sendHelp(player);
        }
        return true;
    }

    private void sendHelp(Player p) {
        String prefix = plugin.getConfigManager().rawMsg("prefix");
        p.sendMessage(com.scorepvp.util.ColorUtil.color(prefix + " " + plugin.getConfigManager().rawStr("friends-help.title")));
        for (String line : plugin.getConfigManager().rawList("friends-help.lines")) {
            p.sendMessage(com.scorepvp.util.ColorUtil.color(line));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) return List.of("add","remove","list","accept","deny","requests")
                .stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        if (args.length == 2 && !List.of("list","requests").contains(args[0].toLowerCase()))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        return List.of();
    }
}
