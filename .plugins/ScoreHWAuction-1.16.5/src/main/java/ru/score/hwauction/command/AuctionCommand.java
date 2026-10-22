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
                sender.sendMessage(plugin.getMessages().raw("player-only"));
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
                // Show the price hint; "auto" as an actual option
                case "sell"  -> filterStart(List.of("auto", plugin.getMessages().raw("tab-complete.price-hint")), args[1]);
                case "admin" -> filterStart(
                        List.of("remove", "clear", "info", "add", "templates", "deltemplate"), args[1]);
                case "search" -> {
                    if (!plugin.getPluginConfig().isEnableSearch()) yield List.of();
                    // Only suggest once the player has typed at least 2 characters.
                    if (args[1].length() < 2) yield List.of();
                    yield searchCatalog(args[1]);
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
                    default      -> filterStart(List.of("full", plugin.getMessages().raw("tab-complete.step-hint")), args[2]);
                };
                case "admin" -> switch (args[1].toLowerCase()) {
                    case "clear" -> {
                        String q = args[2].toLowerCase();
                        yield Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(n -> n.toLowerCase().startsWith(q))
                            .collect(Collectors.toList());
                    }
                    // /ah admin add <category>
                    case "add" -> filterStart(
                            new java.util.ArrayList<>(plugin.getCategoryRegistry().all().keySet()), args[2]);
                    // /ah admin deltemplate <id>
                    case "deltemplate" -> filterStart(
                            plugin.getTemplateRegistry().all().stream()
                                    .map(ru.score.hwauction.model.ItemTemplate::getId)
                                    .collect(Collectors.toList()), args[2]);
                    default -> List.of();
                };
                default -> List.of();
            };
        }

        // /ah sell <price> <step> <time>  (bid auction)
        if (args.length == 4 && args[0].equalsIgnoreCase("sell")
                && !args[2].equalsIgnoreCase("full") && !args[2].equalsIgnoreCase("auto")) {
            return filterStart(List.of(plugin.getMessages().raw("tab-complete.time-hint")), args[3]);
        }

        // /ah admin add <category> <name...> [mode] — suggest match modes as the trailing token
        if (args.length >= 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("add")) {
            return filterStart(
                    List.of("MATERIAL_ENCHANTS", "MATERIAL_LORE", "EXACT"),
                    args[args.length - 1]);
        }

        return List.of();
    }

    /**
     * Full search catalog: every English + Russian vanilla item name plus admin
     * template names, filtered by prefix. Independent of what is currently listed.
     */
    private List<String> searchCatalog(String partial) {
        String q = partial.toLowerCase();
        java.util.TreeSet<String> out = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : plugin.getRuNameProvider().vanillaCatalog()) {
            if (name.toLowerCase().startsWith(q)) out.add(name);
        }
        for (String name : plugin.getTemplateRegistry().allSearchNames()) {
            if (name.toLowerCase().startsWith(q)) out.add(name);
        }
        return out.stream().limit(50).collect(Collectors.toList());
    }

    private List<String> filterStart(List<String> options, String partial) {
        String q = partial.toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(q))
                .collect(Collectors.toList());
    }
}
