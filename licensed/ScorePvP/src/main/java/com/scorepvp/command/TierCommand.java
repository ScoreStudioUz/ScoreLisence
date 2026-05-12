package com.scorepvp.command;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.TierManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

public class TierCommand implements CommandExecutor, TabCompleter {

    private final ScorePvP plugin;
    private final TierManager tm;

    public TierCommand(ScorePvP plugin) {
        this.plugin = plugin;
        this.tm     = plugin.getTierManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {

        if (!sender.hasPermission("scorepvp.admin")) {
            sender.sendMessage(plugin.getConfigManager().msg("no-permission"));
            return true;
        }
        if (args.length < 2) { sendHelp(sender); return true; }

        String sub        = args[0].toLowerCase();
        String targetName = args[1];

        // Resolve player — must be online for tier operations (for cache to work)
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(plugin.getConfigManager().msg("player-not-found", "{player}", targetName));
            return true;
        }
        UUID uuid = target.getUniqueId();

        switch (sub) {
            case "set" -> {
                if (args.length < 4) {
                    sender.sendMessage(plugin.getConfigManager()
                            .msg("invalid-usage", "{usage}", "/tier set <игрок> <режим> <тир>"));
                    return true;
                }
                String mode = args[2];
                String tier = args[3].toUpperCase();

                if (!tm.isValidMode(mode)) {
                    sender.sendMessage(plugin.getConfigManager()
                            .msg("tier.mode-not-found", "{mode}", mode));
                    return true;
                }
                if (!tm.setTier(uuid, mode, tier)) {
                    sender.sendMessage(plugin.getConfigManager()
                            .msg("tier.not-found", "{tier}", tier));
                    return true;
                }
                sender.sendMessage(plugin.getConfigManager().msg("tier.set",
                        "{tier}", tier, "{player}", targetName, "{mode}", mode));
            }

            case "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.getConfigManager()
                            .msg("invalid-usage", "{usage}", "/tier remove <игрок> <режим>"));
                    return true;
                }
                String mode = args[2];
                if (!tm.isValidMode(mode)) {
                    sender.sendMessage(plugin.getConfigManager()
                            .msg("tier.mode-not-found", "{mode}", mode));
                    return true;
                }
                if (!tm.removeTier(uuid, mode)) {
                    sender.sendMessage(plugin.getConfigManager()
                            .msg("tier.mode-not-found", "{mode}", mode));
                    return true;
                }
                sender.sendMessage(plugin.getConfigManager().msg("tier.removed",
                        "{player}", targetName, "{mode}", mode));
            }

            case "info" -> {
                Map<String, String> tiers = tm.getAllTiers(uuid);
                sender.sendMessage(plugin.getConfigManager()
                        .msg("tier.info", "{player}", targetName));
                if (tiers.isEmpty()) {
                    sender.sendMessage(plugin.getConfigManager().rawMsg("tier.no-tiers"));
                } else {
                    tiers.forEach((mode, tier) ->
                            sender.sendMessage(plugin.getConfigManager()
                                    .rawMsg("tier.info-line")
                                    .replace("{mode}", mode)
                                    .replace("{tier}", tier)));
                }
            }

            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender s) {
        String prefix = plugin.getConfigManager().rawMsg("prefix");
        s.sendMessage(com.scorepvp.util.ColorUtil.color(prefix + " " + plugin.getConfigManager().rawStr("tier-help.title")));
        for (String line : plugin.getConfigManager().rawList("tier-help.lines")) {
            s.sendMessage(com.scorepvp.util.ColorUtil.color(line));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("scorepvp.admin")) return List.of();

        return switch (args.length) {
            case 1 -> filter(List.of("set", "remove", "info"), args[0]);
            case 2 -> Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            case 3 -> {
                String sub = args[0].toLowerCase();
                if (sub.equals("set") || sub.equals("remove"))
                    yield filter(new ArrayList<>(plugin.getConfigManager().getModeMap().keySet()), args[2]);
                yield List.of();
            }
            case 4 -> {
                if (args[0].equalsIgnoreCase("set"))
                    yield filter(plugin.getConfigManager().getTierOrder(), args[3].toUpperCase());
                yield List.of();
            }
            default -> List.of();
        };
    }

    private List<String> filter(List<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }
}
