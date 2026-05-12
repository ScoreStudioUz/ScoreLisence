package com.scorepvp.command;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.PartyManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

public class PartyCommand implements CommandExecutor, TabCompleter {

    private final ScorePvP plugin;
    private final PartyManager pm;

    public PartyCommand(ScorePvP plugin) {
        this.plugin = plugin;
        this.pm = plugin.getPartyManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().rawMsg("players-only"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "invite" -> {
                if (args.length < 2) { player.sendMessage(plugin.getConfigManager()
                        .msg("invalid-usage", "{usage}", "/party invite <ник>")); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { player.sendMessage(plugin.getConfigManager()
                        .msg("player-not-found", "{player}", args[1])); return true; }
                if (target.equals(player)) { player.sendMessage(plugin.getConfigManager()
                        .msg("party.cannot-invite-self")); return true; }
                pm.invite(player, target);
            }
            case "accept" -> {
                if (args.length < 2) { player.sendMessage(plugin.getConfigManager()
                        .msg("invalid-usage", "{usage}", "/party accept <лидер>")); return true; }
                pm.accept(player, args[1]);
            }
            case "leave" -> pm.leave(player);
            case "kick" -> {
                if (args.length < 2) { player.sendMessage(plugin.getConfigManager()
                        .msg("invalid-usage", "{usage}", "/party kick <ник>")); return true; }
                if (args[1].equalsIgnoreCase(player.getName())) {
                    player.sendMessage(plugin.getConfigManager().msg("party.cannot-kick-self")); return true;
                }
                pm.kick(player, args[1]);
            }
            case "disband" -> pm.disband(player);
            case "chat"    -> pm.toggleChat(player);
            case "promote" -> {
                if (args.length < 2) { player.sendMessage(plugin.getConfigManager()
                        .msg("invalid-usage", "{usage}", "/party promote <ник>")); return true; }
                pm.promote(player, args[1]);
            }
            case "deny" -> {
                if (args.length < 2) { player.sendMessage(plugin.getConfigManager()
                        .msg("invalid-usage", "{usage}", "/party deny <лидер>")); return true; }
                pm.denyInvite(player, args[1]);
            }
            case "ffa" -> {
                if (!pm.isInParty(player.getUniqueId())) {
                    player.sendMessage(plugin.getConfigManager().msg("party.not-in-party")); return true;
                }
                if (!pm.isLeader(player.getUniqueId())) {
                    player.sendMessage(plugin.getConfigManager().msg("party.not-leader")); return true;
                }
                plugin.getPartyFfaGUI().open(player);
            }
            default -> sendHelp(player);
        }

        return true;
    }

    private void sendHelp(Player player) {
        String prefix = plugin.getConfigManager().rawMsg("prefix");
        player.sendMessage(com.scorepvp.util.ColorUtil.color(prefix + " " + plugin.getConfigManager().rawStr("party-help.title")));
        for (String line : plugin.getConfigManager().rawList("party-help.lines")) {
            player.sendMessage(com.scorepvp.util.ColorUtil.color(line));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("invite", "accept", "deny", "leave", "kick", "disband", "chat", "promote", "ffa")
                    .stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && List.of("invite","accept","kick","promote").contains(args[0].toLowerCase())) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
