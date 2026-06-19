package com.scorepvp.command;

import com.scorepvp.ScorePvP;
import com.scorepvp.listener.ArenaToolListener;
import com.scorepvp.model.Arena;
import com.scorepvp.model.Match;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

// ─────────────────────────────────────────────────────────────────────────────
//  /spec <ник>
// ─────────────────────────────────────────────────────────────────────────────
class SpecCommand implements CommandExecutor, TabCompleter {
    private final ScorePvP p;
    SpecCommand(ScorePvP p) { this.p = p; }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] args) {
        if (!(s instanceof Player spec)) { s.sendMessage(p.getConfigManager().rawMsg("players-only")); return true; }

        if (args.length < 1) {
            spec.sendMessage(p.getConfigManager().msg("invalid-usage", "{usage}", "/spec <ник>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            spec.sendMessage(p.getConfigManager().msg("player-not-found", "{player}", args[0]));
            return true;
        }
        Match match = p.getMatchManager().getMatch(target.getUniqueId());
        if (match == null || !match.isFighter(target.getUniqueId())) {
            spec.sendMessage(p.getConfigManager().rawMsg("prefix") + " " + p.getConfigManager().rawMsg("spec.not-in-match"));
            return true;
        }
        if (p.getMatchManager().isInMatch(spec.getUniqueId())) {
            spec.sendMessage(p.getConfigManager().rawMsg("prefix") + " " + p.getConfigManager().rawMsg("spec.cannot-while-match"));
            return true;
        }
        if (p.getDuelManager().isInQueue(spec.getUniqueId())) {
            spec.sendMessage(p.getConfigManager().rawMsg("prefix") + " " + p.getConfigManager().rawMsg("spec.leave-queue-first"));
            return true;
        }
        if (!p.getMatchManager().addSpectator(spec, match)) {
            spec.sendMessage(p.getConfigManager().rawMsg("prefix") + " " + p.getConfigManager().rawMsg("spec.cannot-spectate"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] a) {
        if (a.length == 1) return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName).filter(n -> n.toLowerCase().startsWith(a[0].toLowerCase())).collect(Collectors.toList());
        return List.of();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  /leave  — leave match, queue, or spectator
//  FIX (section 3): In Party FFA, /leave converts fighter to spectator instead
//  of immediately removing them.
// ─────────────────────────────────────────────────────────────────────────────
class LeaveCommand implements CommandExecutor {
    private final ScorePvP p;
    LeaveCommand(ScorePvP p) { this.p = p; }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] a) {
        if (!(s instanceof Player player)) { s.sendMessage(p.getConfigManager().rawMsg("players-only")); return true; }
        UUID uuid = player.getUniqueId();

        if (p.getDuelManager().isInQueue(uuid)) {
            p.getDuelManager().leaveQueue(player);
            return true;
        }
        if (p.getMatchManager().isInMatch(uuid)) {
            if (p.getMatchManager().isFighter(uuid)) {
                Match match = p.getMatchManager().getMatch(uuid);
                // FIX: Party FFA — convert to spectator instead of forfeit
                if (match != null && match.isFfa()) {
                    com.scorepvp.model.MatchPlayer mp = match.getFighter(uuid);
                    if (mp != null && !mp.isDead()) {
                        p.getMatchManager().convertToSpectator(player, match);
                        player.sendMessage(p.getConfigManager().rawMsg("prefix")
                                + " " + p.getConfigManager().rawMsg("match.spectate-left"));
                        return true;
                    }
                }
                // Duel: normal disconnect/forfeit
                p.getMatchManager().handleDisconnect(uuid);
            } else {
                p.getMatchManager().removeSpectator(player);
            }
            return true;
        }
        player.sendMessage(p.getConfigManager().rawMsg("prefix") + " " + p.getConfigManager().rawMsg("leave.nowhere"));
        return true;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  /msg <ник> <сообщение>   and   /r <сообщение>
// ─────────────────────────────────────────────────────────────────────────────
class MsgCommand implements CommandExecutor, TabCompleter {
    private final ScorePvP p;
    private final boolean isReply;
    MsgCommand(ScorePvP p, boolean isReply) { this.p = p; this.isReply = isReply; }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] args) {
        if (!(s instanceof Player sender)) { s.sendMessage(p.getConfigManager().rawMsg("players-only")); return true; }

        if (isReply) {
            if (args.length < 1) { sender.sendMessage(p.getConfigManager().msg("invalid-usage", "{usage}", "/r <сообщение>")); return true; }
            p.getMsgManager().reply(sender, String.join(" ", args));
            return true;
        }

        if (args.length < 2) { sender.sendMessage(p.getConfigManager().msg("invalid-usage", "{usage}", "/msg <ник> <сообщение>")); return true; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { sender.sendMessage(p.getConfigManager().msg("player-not-found", "{player}", args[0])); return true; }
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        p.getMsgManager().send(sender, target, message);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] a) {
        if (!isReply && a.length == 1) return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName).filter(n -> n.toLowerCase().startsWith(a[0].toLowerCase())).collect(Collectors.toList());
        return List.of();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  /arena create <n>  |  /arena <n> ...  |  /arena list  |  /arena delete <n>
// ─────────────────────────────────────────────────────────────────────────────
class ArenaCommand implements CommandExecutor, TabCompleter {
    private final ScorePvP p;
    ArenaCommand(ScorePvP p) { this.p = p; }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] args) {
        if (!s.hasPermission("scorepvp.admin")) { s.sendMessage(p.getConfigManager().msg("no-permission")); return true; }
        if (!(s instanceof Player player)) { s.sendMessage(p.getConfigManager().rawMsg("players-only")); return true; }
        if (args.length == 0) { sendHelp(player); return true; }

        switch (args[0].toLowerCase()) {
            case "create" -> {
                if (args.length < 2) { player.sendMessage(p.getConfigManager().rawMsg("arena.create-usage")); return true; }
                try {
                    int n = Integer.parseInt(args[1]);
                    p.getArenaManager().startCreation(player, n);
                    player.getInventory().addItem(new org.bukkit.inventory.ItemStack(com.scorepvp.listener.ArenaToolListener.TOOL));
                } catch (NumberFormatException ex) { player.sendMessage(p.getConfigManager().rawMsg("arena.number-required")); }
            }
            case "list" -> {
                player.sendMessage(p.getConfigManager().rawMsg("prefix") + " " +
                        p.getConfigManager().rawMsg("arena.list-header",
                                "{count}", String.valueOf(p.getArenaManager().getAllArenas().size())));
                p.getArenaManager().getAllArenas().forEach(a -> {
                    String statusStr = a.isFree()
                            ? p.getConfigManager().rawStr("arena.list-entry-free")
                            : p.getConfigManager().rawStr("arena.list-entry-busy");
                    player.sendMessage(com.scorepvp.util.ColorUtil.color(
                            "  &7#" + a.getNumber() + (a.getName().isEmpty() ? "" : " &b" + a.getName())
                            + " &8[" + a.getType().name().toLowerCase() + "]"
                            + " " + (a.isFullyConfigured() ? "&a\u2714" : "&c\u2718")
                            + " " + statusStr));
                });
            }
            case "delete" -> {
                if (args.length < 2) return true;
                try { int n = Integer.parseInt(args[1]); p.getArenaManager().deleteArena(n);
                    player.sendMessage(p.getConfigManager().rawMsg("prefix") + " " +
                            p.getConfigManager().rawMsg("arena.deleted",
                                    "{number}", String.valueOf(n))); }
                catch (NumberFormatException ex) { player.sendMessage(p.getConfigManager().rawMsg("arena.number-required")); }
            }
            default -> {
                int n;
                try { n = Integer.parseInt(args[0]); }
                catch (NumberFormatException ex) { sendHelp(player); return true; }

                if (args.length < 2) { p.getArenaManager().showInfo(player, n); return true; }

                switch (args[1].toLowerCase()) {
                    case "info"     -> p.getArenaManager().showInfo(player, n);
                    case "name"     -> {
                        if (args.length < 3) { player.sendMessage(p.getConfigManager().rawMsg("arena.name-usage")); return true; }
                        p.getArenaManager().setName(player, n, args[2]);
                    }
                    case "type"     -> {
                        if (args.length < 3) { player.sendMessage(p.getConfigManager().rawMsg("arena.type-usage")); return true; }
                        p.getArenaManager().setType(player, n, args[2]);
                    }
                    case "save"     -> p.getArenaRestoreManager().scanAndSave(p.getArenaManager().getArena(n), player);
                    case "restore"  -> {
                        Arena arena = p.getArenaManager().getArena(n);
                        if (arena == null) { player.sendMessage(p.getConfigManager().rawMsg("arena.arena-not-found")); return true; }
                        p.getArenaRestoreManager().manualRestore(arena, player);
                    }
                    case "position" -> {
                        if (args.length >= 4 && args[2].equalsIgnoreCase("set"))
                            p.getArenaManager().setPosition(player, n, args[3]);
                        else sendHelp(player);
                    }
                    case "delete"   -> p.getArenaManager().deleteArena(n);
                    default         -> sendHelp(player);
                }
            }
        }
        return true;
    }

    private void sendHelp(Player pl) {
        pl.sendMessage(com.scorepvp.util.ColorUtil.color(p.getConfigManager().rawStr("arena.help-title")));
        for (String line : p.getConfigManager().rawList("arena.help")) {
            pl.sendMessage(com.scorepvp.util.ColorUtil.color(line));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] a) {
        if (!s.hasPermission("scorepvp.admin")) return List.of();
        if (a.length == 1) return List.of("create","list");
        if (a.length == 2) return List.of("name","type","position","save","restore","info","delete");
        if (a.length == 3 && a[1].equalsIgnoreCase("type")) return List.of("default","vanilla");
        if (a.length == 3 && a[1].equalsIgnoreCase("position")) return List.of("set");
        if (a.length == 4 && a[1].equalsIgnoreCase("position")) return List.of("1","2","center");
        return List.of();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  /profile [ник]
// ─────────────────────────────────────────────────────────────────────────────
class ProfileCommand implements CommandExecutor, TabCompleter {
    private final ScorePvP p;
    ProfileCommand(ScorePvP p) { this.p = p; }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] args) {
        if (!(s instanceof Player viewer)) { s.sendMessage(p.getConfigManager().rawMsg("players-only")); return true; }
        if (args.length == 0) {
            p.getProfileGUI().open(viewer, viewer);
        } else {
            org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (target == null) { viewer.sendMessage(p.getConfigManager().msg("player-not-found", "{player}", args[0])); return true; }
            p.getProfileGUI().open(viewer, target);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] a) {
        if (a.length == 1) return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName).filter(n -> n.toLowerCase().startsWith(a[0].toLowerCase())).collect(Collectors.toList());
        return List.of();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  /stopduel <ник>  — admin: force-end a player's duel as a DRAW
// ─────────────────────────────────────────────────────────────────────────────
class StopDuelCommand implements CommandExecutor, TabCompleter {
    private final ScorePvP p;
    StopDuelCommand(ScorePvP p) { this.p = p; }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] args) {
        if (!s.hasPermission("scorepvp.stopduel")) { s.sendMessage(p.getConfigManager().msg("no-permission")); return true; }
        if (args.length < 1) {
            s.sendMessage(p.getConfigManager().rawMsg("prefix") + " " + p.getConfigManager().rawMsg("match.admin-draw-usage"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            s.sendMessage(p.getConfigManager().msg("player-not-found", "{player}", args[0]));
            return true;
        }
        Match match = p.getMatchManager().getMatch(target.getUniqueId());
        if (match == null || !match.isFighter(target.getUniqueId())) {
            s.sendMessage(p.getConfigManager().rawMsg("prefix") + " " + p.getConfigManager().rawMsg("match.admin-draw-not-in-duel", "{player}", target.getName()));
            return true;
        }
        p.getMatchManager().forceDraw(match);
        s.sendMessage(p.getConfigManager().rawMsg("prefix") + " " + p.getConfigManager().rawMsg("match.admin-draw-done", "{player}", target.getName()));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] a) {
        if (!s.hasPermission("scorepvp.stopduel")) return List.of();
        if (a.length == 1) return Bukkit.getOnlinePlayers().stream()
                .filter(pl -> p.getMatchManager().isFighter(pl.getUniqueId()))
                .map(Player::getName).filter(n -> n.toLowerCase().startsWith(a[0].toLowerCase())).collect(Collectors.toList());
        return List.of();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Register all commands
// ─────────────────────────────────────────────────────────────────────────────
public class CommandRegistry {

    public static void register(ScorePvP plugin) {
        reg(plugin, "spec",    new SpecCommand(plugin));
        reg(plugin, "leave",   new LeaveCommand(plugin));
        reg(plugin, "msg",     new MsgCommand(plugin, false));
        reg(plugin, "r",       new MsgCommand(plugin, true));
        reg(plugin, "arena",   new ArenaCommand(plugin));
        reg(plugin, "profile", new ProfileCommand(plugin));
        reg(plugin, "duel",    new DuelCommand(plugin));
        reg(plugin, "stopduel", new StopDuelCommand(plugin));
    }

    private static void reg(ScorePvP plugin, String name, Object handler) {
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd == null) { plugin.getLogger().warning("[ScorePvP] Command /" + name + " missing from plugin.yml"); return; }
        if (handler instanceof CommandExecutor ce) cmd.setExecutor(ce);
        if (handler instanceof TabCompleter tc)    cmd.setTabCompleter(tc);
    }
}
