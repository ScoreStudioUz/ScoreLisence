package uz.score.scorecheck.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uz.score.scorecheck.ScoreCheck;
import uz.score.scorecheck.managers.CheckManager;
import uz.score.scorecheck.managers.CheckManager.CloseReason;
import uz.score.scorecheck.managers.CheckSession;
import uz.score.scorecheck.managers.ConfigManager;
import uz.score.scorecheck.utils.ColorUtil;
import uz.score.scorecheck.utils.TimeUtil;

import java.util.*;
import java.util.stream.Collectors;

public class CheckCommand implements CommandExecutor, TabCompleter {

    private final ScoreCheck    plugin;
    private final CheckManager  manager;
    private final ConfigManager cfg;

    public CheckCommand(ScoreCheck plugin) {
        this.plugin  = plugin;
        this.manager = plugin.getCheckManager();
        this.cfg     = plugin.getConfigManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {

            // /check start <player>
            case "start" -> {
                if (!perm(sender, "scorecheck.start")) return true;
                if (!mustBePlayer(sender))             return true;
                if (args.length < 2) { sendHelp(sender); return true; }

                Player mod    = (Player) sender;
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { msg(sender, "player-not-found", args[1]); return true; }
                if (manager.isInCheck(target.getUniqueId())) { msg(sender, "player-already-checked", target.getName()); return true; }
                manager.startCheck(mod, target);
            }

            // /check close <player> [pass|ban]
            case "close" -> {
                if (!perm(sender, "scorecheck.close")) return true;
                if (!mustBePlayer(sender))             return true;
                if (args.length < 2) { sendHelp(sender); return true; }

                Player mod = (Player) sender;
                UUID uid   = resolveChecked(args[1], sender);
                if (uid == null) return true;

                String sub = args.length >= 3 ? args[2].toLowerCase() : "pass";
                CloseReason reason = sub.equals("ban") ? CloseReason.BAN : CloseReason.PASS;
                manager.closeCheck(uid, reason, mod);
            }

            // /check free <player>  — alias for close pass
            case "free" -> {
                if (!perm(sender, "scorecheck.free")) return true;
                if (!mustBePlayer(sender))            return true;
                if (args.length < 2) { sendHelp(sender); return true; }

                Player mod = (Player) sender;
                UUID uid   = resolveChecked(args[1], sender);
                if (uid == null) return true;
                manager.closeCheck(uid, CloseReason.PASS, mod);
            }

            // /check next <player>
            case "next" -> {
                if (!perm(sender, "scorecheck.next")) return true;
                if (!mustBePlayer(sender))            return true;
                if (args.length < 2) { sendHelp(sender); return true; }

                Player mod = (Player) sender;
                UUID uid   = resolveChecked(args[1], sender);
                if (uid == null) return true;

                if (!manager.nextPhase(uid, mod)) msg(sender, "player-not-checked", args[1]);
            }

            // /check msg <player> <message...>
            case "msg", "message" -> {
                if (!perm(sender, "scorecheck.msg")) return true;
                if (!mustBePlayer(sender))           return true;
                if (args.length < 3) { sendHelp(sender); return true; }

                Player mod = (Player) sender;
                UUID uid   = resolveChecked(args[1], sender);
                if (uid == null) return true;

                String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                if (!manager.sendMessage(mod, uid, message)) msg(sender, "player-not-checked", args[1]);
            }

            // /check addtime <player> <time>
            case "addtime" -> {
                if (!perm(sender, "scorecheck.addtime")) return true;
                if (!mustBePlayer(sender))               return true;
                if (args.length < 3) { sendHelp(sender); return true; }

                Player mod = (Player) sender;
                UUID uid   = resolveChecked(args[1], sender);
                if (uid == null) return true;

                int secs = parseTime(sender, args[2]);
                if (secs < 0) return true;
                if (!manager.addTime(uid, secs, mod)) msg(sender, "player-not-checked", args[1]);
            }

            // /check remtime <player> <time>
            case "remtime" -> {
                if (!perm(sender, "scorecheck.remtime")) return true;
                if (!mustBePlayer(sender))               return true;
                if (args.length < 3) { sendHelp(sender); return true; }

                Player mod = (Player) sender;
                UUID uid   = resolveChecked(args[1], sender);
                if (uid == null) return true;

                int secs = parseTime(sender, args[2]);
                if (secs < 0) return true;
                if (!manager.removeTime(uid, secs, mod)) msg(sender, "player-not-checked", args[1]);
            }

            // /check settime <player> <time>
            case "settime" -> {
                if (!perm(sender, "scorecheck.settime")) return true;
                if (!mustBePlayer(sender))               return true;
                if (args.length < 3) { sendHelp(sender); return true; }

                Player mod = (Player) sender;
                UUID uid   = resolveChecked(args[1], sender);
                if (uid == null) return true;

                int secs = parseTime(sender, args[2]);
                if (secs < 0) return true;
                if (!manager.setTime(uid, secs, mod)) msg(sender, "player-not-checked", args[1]);
            }

            // /check list
            case "list" -> {
                if (!perm(sender, "scorecheck.list")) return true;

                Collection<CheckSession> all = manager.getAllSessions();
                if (all.isEmpty()) {
                    ColorUtil.send(sender, cfg.getMessage("list-empty"));
                    return true;
                }

                ColorUtil.send(sender,
                        cfg.getMessage("list-header").replace("{count}", String.valueOf(all.size())));

                for (CheckSession s : all) {
                    String phaseLabel = s.isPhase(CheckSession.Phase.WAITING)
                            ? cfg.getMessage("info-phase1")
                            : cfg.getMessage("info-phase2");
                    String timer = s.isPhase(CheckSession.Phase.WAITING)
                            ? TimeUtil.format(s.getRemainingSeconds())
                            : "&e∞";
                    String entry = cfg.getMessage("list-entry")
                            .replace("{player}",    s.getPlayerName())
                            .replace("{moderator}", s.getModeratorName())
                            .replace("{phase}",     phaseLabel)
                            .replace("{timer}",     timer);
                    ColorUtil.send(sender, entry);
                }
            }

            // /check info <player>
            case "info" -> {
                if (!perm(sender, "scorecheck.info")) return true;
                if (args.length < 2) { sendHelp(sender); return true; }

                UUID uid = resolveChecked(args[1], sender);
                if (uid == null) return true;
                CheckSession s = manager.getSession(uid);

                String phaseLabel = s.isPhase(CheckSession.Phase.WAITING)
                        ? cfg.getMessage("info-phase1")
                        : cfg.getMessage("info-phase2");
                String timer = s.isPhase(CheckSession.Phase.WAITING)
                        ? TimeUtil.format(s.getRemainingSeconds())
                        : "&e∞";
                String info = cfg.getMessage("info")
                        .replace("{player}",    s.getPlayerName())
                        .replace("{moderator}", s.getModeratorName())
                        .replace("{phase}",     phaseLabel)
                        .replace("{timer}",     timer);
                ColorUtil.sendLines(sender, info);
            }

            // /check setloc
            case "setloc" -> {
                if (!perm(sender, "scorecheck.setloc")) return true;
                if (!mustBePlayer(sender))              return true;

                Player mod = (Player) sender;
                cfg.setTeleportLocation(mod.getLocation());
                ColorUtil.send(sender, cfg.getMessage("location-saved"));
            }

            // /check reload
            case "reload" -> {
                if (!perm(sender, "scorecheck.reload")) return true;
                plugin.reload();
                ColorUtil.send(sender, cfg.getMessage("config-reloaded"));
            }

            // /check help
            case "help" -> sendHelp(sender);

            default -> ColorUtil.send(sender, cfg.getMessage("unknown-sub"));
        }

        return true;
    }

    // ================================================================
    //  TAB COMPLETION
    // ================================================================
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String label,
                                                 @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = List.of("start", "close", "free", "next", "msg",
                    "addtime", "remtime", "settime", "list", "info", "setloc", "reload", "help");
            return filter(subs, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("start")) {
                // Online players NOT in check
                return Bukkit.getOnlinePlayers().stream()
                        .filter(p -> !manager.isInCheck(p.getUniqueId()))
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (List.of("close", "free", "next", "msg", "addtime", "remtime", "settime", "info").contains(sub)) {
                // Players currently in check
                return manager.getAllSessions().stream()
                        .map(CheckSession::getPlayerName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if (sub.equals("close")) return filter(List.of("pass", "ban"), args[2]);
            if (List.of("addtime", "remtime", "settime").contains(sub))
                return filter(List.of("30s", "1m", "5m", "10m", "30m", "1h"), args[2]);
        }

        return Collections.emptyList();
    }

    // ================================================================
    //  Helpers
    // ================================================================
    private boolean perm(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        ColorUtil.send(sender, cfg.getMessage("no-permission"));
        return false;
    }

    private boolean mustBePlayer(CommandSender sender) {
        if (sender instanceof Player) return true;
        ColorUtil.send(sender, cfg.getMessage("player-only"));
        return false;
    }

    private UUID resolveChecked(String name, CommandSender sender) {
        UUID uid = manager.getUUIDByName(name);
        if (uid == null) msg(sender, "player-not-checked", name);
        return uid;
    }

    private int parseTime(CommandSender sender, String input) {
        int s = TimeUtil.parseTime(input);
        if (s < 0) ColorUtil.send(sender, cfg.getMessage("invalid-time"));
        return s;
    }

    private void msg(CommandSender sender, String key, String player) {
        ColorUtil.send(sender, cfg.getMessage(key).replace("{player}", player));
    }

    private void sendHelp(CommandSender sender) {
        ColorUtil.sendLines(sender, cfg.getMessage("help"));
    }

    private List<String> filter(List<String> list, String input) {
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(input.toLowerCase()))
                .collect(Collectors.toList());
    }
}
