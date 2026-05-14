package com.scorepvp.command;

import com.scorepvp.ScorePvP;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

public class DuelCommand implements CommandExecutor, TabCompleter {

    private final ScorePvP plugin;

    public DuelCommand(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(plugin.getConfigManager().rawMsg("players-only")); return true; }

        // /duel accept [name]
        if (args.length >= 1 && args[0].equalsIgnoreCase("accept")) {
            if (args.length >= 2) {
                plugin.getDuelManager().acceptDuelFrom(player, args[1]);
            } else {
                plugin.getDuelManager().acceptDuel(player);
            }
            return true;
        }

        // /duel decline [name]
        if (args.length >= 1 && args[0].equalsIgnoreCase("decline")) {
            plugin.getDuelManager().declineDuel(player);
            return true;
        }

        // /duel <ник>
        if (args.length < 1) {
            player.sendMessage(plugin.getConfigManager().msg("invalid-usage", "{usage}", "/duel <ник>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(plugin.getConfigManager().msg("player-not-found", "{player}", args[0]));
            return true;
        }
        if (target.equals(player)) {
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix") + " " + plugin.getConfigManager().rawMsg("duel.cannot-self"));
            return true;
        }
        if (plugin.getPartyManager().isInParty(player.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().msg("party.pvp-blocked"));
            return true;
        }

        plugin.getDuelModeGUI().open(player, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
