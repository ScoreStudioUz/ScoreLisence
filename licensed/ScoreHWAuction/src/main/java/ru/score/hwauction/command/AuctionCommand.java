package ru.score.hwauction.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.command.sub.*;

import java.util.List;
import java.util.stream.Collectors;

public final class AuctionCommand implements CommandExecutor, TabCompleter {

    private final ScoreHWAuction plugin;

    private final SellSubCommand   sellCmd;
    private final SearchSubCommand searchCmd;
    private final PlayerSubCommand playerCmd;
    private final SoundSubCommand  soundCmd;
    private final HelpSubCommand   helpCmd;
    private final ReloadSubCommand reloadCmd;
    private final AdminSubCommand  adminCmd;

    public AuctionCommand(ScoreHWAuction plugin) {
        this.plugin = plugin;
        sellCmd   = new SellSubCommand(plugin);
        searchCmd = new SearchSubCommand(plugin);
        playerCmd = new PlayerSubCommand(plugin);
        soundCmd  = new SoundSubCommand(plugin);
        helpCmd   = new HelpSubCommand(plugin);
        reloadCmd = new ReloadSubCommand(plugin);
        adminCmd  = new AdminSubCommand(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
                reloadCmd.execute(sender, args);
            } else {
                sender.sendMessage("[ScoreHWAuction] Player-only command.");
            }
            return true;
        }

        if (!player.hasPermission("scoreah.use")) {
            player.sendMessage(plugin.getMessages().get("no-permission"));
            return true;
        }

        if (args.length == 0) {
            plugin.getSessionManager().getOrCreate(player).resetFilters();
            plugin.getMainGui().open(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "sell"   -> sellCmd.execute(player, args);
            case "search" -> {
                if (!plugin.getPluginConfig().isEnableSearch()) {
                    player.sendMessage(plugin.getMessages().get("feature-disabled")); return true;
                }
                searchCmd.execute(player, args);
            }
            case "player" -> {
                if (!plugin.getPluginConfig().isEnablePlayerFilter()) {
                    player.sendMessage(plugin.getMessages().get("feature-disabled")); return true;
                }
                playerCmd.execute(player, args);
            }
            case "sound"  -> soundCmd.execute(player, args);
            case "help"   -> helpCmd.execute(player);
            case "reload" -> reloadCmd.execute(sender, args);
            case "admin"  -> adminCmd.execute(player, args);
            default       -> player.sendMessage(plugin.getMessages().get("unknown-command"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterStart(
                List.of("sell", "search", "player", "sound", "help", "reload", "admin"),
                args[0]);
        }

        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                // Show [цена] as a hint; "auto" as an actual option
                case "sell"  -> filterStart(List.of("auto", "[цена]"), args[1]);
                case "admin" -> filterStart(List.of("remove", "clear", "info"), args[1]);
                case "search" -> {
                    if (!plugin.getPluginConfig().isEnableSearch()) yield List.of();
                    String q = args[1].toLowerCase();
                    yield plugin.getAuctionCache().active().stream()
                        .map(i -> ru.score.hwauction.util.ItemUtil.getDisplayName(i.getItemStack()))
                        .distinct()
                        .filter(n -> n.toLowerCase().startsWith(q))
                        .limit(10)
                        .sorted()
                        .collect(Collectors.toList());
                }
                case "player" -> {
                    if (!plugin.getPluginConfig().isEnablePlayerFilter()) yield List.of();
                    String q = args[1].toLowerCase();
                    yield Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(q))
                        .sorted()
                        .collect(Collectors.toList());
                }
                default -> List.of();
            };
        }

        if (args.length == 3) {
            return switch (args[0].toLowerCase()) {
                case "sell" -> switch (args[1].toLowerCase()) {
                    case "auto"  -> filterStart(List.of("confirm"), args[2]);
                    default      -> filterStart(List.of("full", "[шаг]"), args[2]);
                };
                case "admin" -> switch (args[1].toLowerCase()) {
                    case "clear" -> {
                        String q = args[2].toLowerCase();
                        yield Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(n -> n.toLowerCase().startsWith(q))
                            .collect(Collectors.toList());
                    }
                    default -> List.of();
                };
                default -> List.of();
            };
        }

        // /ah sell <price> <step> [время]  (bid auction)
        if (args.length == 4 && args[0].equalsIgnoreCase("sell")
                && !args[2].equalsIgnoreCase("full") && !args[2].equalsIgnoreCase("auto")) {
            return filterStart(List.of("[время]"), args[3]);
        }

        return List.of();
    }

    private List<String> filterStart(List<String> options, String partial) {
        String q = partial.toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(q))
                .collect(Collectors.toList());
    }
}
