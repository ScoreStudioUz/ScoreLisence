package com.scorepvp.command;

import com.scorepvp.ScorePvP;
import com.scorepvp.item.CustomItemRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /scorepvp (alias /spvp) — admin commands.
 *
 * Section 4 additions:
 *   /spvp kit create <name>   — create a new empty kit file
 *   /spvp kit save <name>     — save current inventory as a named kit
 *   /spvp kit set vanilla        — set current inventory as default vanilla kit
 *
 * Section 7 addition:
 *   /spvp giveitem <player> <item_id>  — give a custom item to a player
 */
public class ScorePvPCommand implements CommandExecutor, TabCompleter {

    private final ScorePvP plugin;

    public ScorePvPCommand(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {

        if (!sender.hasPermission("scorepvp.admin")) {
            sender.sendMessage(plugin.getConfigManager().msg("no-permission"));
            return true;
        }

        String sub = (args.length > 0) ? args[0].toLowerCase() : "help";

        switch (sub) {
            case "reload" -> {
                long start = System.currentTimeMillis();
                // Before reloading config: tear down every active match —
                // restore vanilla arenas, cancel all duels/queues, send
                // everyone to spawn. Otherwise a reload mid-duel leaves arenas
                // griefed and players stuck with stale match state.
                plugin.getDuelManager().clearAllQueues();
                plugin.getMatchManager().shutdownAllMatches();
                plugin.getConfigManager().reload();
                plugin.getMenuManager().loadAll();
                // Reload all item_menus GUI configs
                plugin.getMainMenuGUI().loadConfig();
                plugin.getSettingsGUI().loadConfig();
                plugin.getDuelModeGUI().loadConfig();
                plugin.getDuelRoundsGUI().loadConfig();
                plugin.getPartyFfaGUI().loadConfig();
                plugin.getProfileGUI().loadConfig();
                long ms = System.currentTimeMillis() - start;
                sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                        + " " + plugin.getConfigManager().rawMsg("admin.reload", "{ms}", String.valueOf(ms)));
            }

            case "version" -> {
                sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                        + " " + plugin.getConfigManager().rawMsg("admin.version", "{version}", plugin.getDescription().getVersion()));
                String dbType = (plugin.getStorage().getTiers()
                        instanceof com.scorepvp.storage.MysqlTierStorage
                        ? plugin.getConfigManager().rawMsg("admin.version-db-mysql")
                        : plugin.getConfigManager().rawMsg("admin.version-db-sqlite"));
                sender.sendMessage(plugin.getConfigManager().rawMsg("admin.version-db", "{db}", dbType));
                sender.sendMessage(plugin.getConfigManager().rawMsg("admin.version-modes", "{count}",
                        String.valueOf(plugin.getConfigManager().getModes().size())));
            }

            case "setspawn" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage(plugin.getConfigManager().rawMsg("admin.setspawn-only-player")); return true; }
                org.bukkit.Location loc = player.getLocation();
                plugin.getConfig().set("warp.world", loc.getWorld().getName());
                plugin.getConfig().set("warp.x", loc.getX());
                plugin.getConfig().set("warp.y", loc.getY());
                plugin.getConfig().set("warp.z", loc.getZ());
                plugin.getConfig().set("warp.yaw",   (double) loc.getYaw());
                plugin.getConfig().set("warp.pitch",  (double) loc.getPitch());
                plugin.saveConfig();
                sender.sendMessage(plugin.getConfigManager().rawMsg("prefix") + " " + plugin.getConfigManager().rawMsg("admin.setspawn-done"));
            }

            case "settpspawn" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage(plugin.getConfigManager().rawMsg("admin.settpspawn-only-player")); return true; }
                org.bukkit.Location tpLoc = player.getLocation();
                plugin.getConfig().set("tp-spawn.world", tpLoc.getWorld().getName());
                plugin.getConfig().set("tp-spawn.x", tpLoc.getX());
                plugin.getConfig().set("tp-spawn.y", tpLoc.getY());
                plugin.getConfig().set("tp-spawn.z", tpLoc.getZ());
                plugin.getConfig().set("tp-spawn.yaw",   (double) tpLoc.getYaw());
                plugin.getConfig().set("tp-spawn.pitch",  (double) tpLoc.getPitch());
                plugin.saveConfig();
                plugin.getConfigManager().reload();
                sender.sendMessage(plugin.getConfigManager().rawMsg("prefix") + " " + plugin.getConfigManager().rawMsg("admin.settpspawn-done"));
            }

            case "setregion" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage(plugin.getConfigManager().rawMsg("admin.setregion-only-player")); return true; }
                plugin.getArenaManager().startRegionSelection(player);
            }

            // ── Section 3: Kit editor ────────────────────────────────────────

            case "editor" -> {
                if (!(sender instanceof Player admin)) {
                    sender.sendMessage(plugin.getConfigManager().rawMsg("admin.editor-only-player"));
                    return true;
                }
                boolean active = plugin.getPlayerDataManager().isKitEditorMode(admin.getUniqueId());
                if (!active) {
                    // Enter editor mode — hide hotbar items
                    plugin.getPlayerDataManager().setKitEditorMode(admin.getUniqueId(), true);
                    plugin.getHotbarManager().removeHotbar(admin);
                    admin.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                            + " " + plugin.getConfigManager().rawMsg("admin.editor-on"));
                } else {
                    // Exit editor mode — restore hotbar
                    plugin.getPlayerDataManager().setKitEditorMode(admin.getUniqueId(), false);
                    plugin.getHotbarManager().restoreHotbar(admin);
                    admin.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                            + " " + plugin.getConfigManager().rawMsg("admin.editor-off"));
                }
            }

            // ── Section 4: Kit Management ────────────────────────────────────

            case "kit" -> {
                if (args.length < 2) { sendHelp(sender); return true; }
                String kitSub = args[1].toLowerCase();
                switch (kitSub) {
                    case "create" -> {
                        if (args.length < 3) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.kit-create-usage"));
                            return true;
                        }
                        // Accept multi-word kit names — admins occasionally type
                        // "/spvp kit create my new kit" and used to silently get
                        // a file called "my.yml".
                        String rawName = String.join("_", java.util.Arrays.copyOfRange(args, 2, args.length));
                        String kitName = com.scorepvp.manager.KitManager.sanitizeKitName(rawName);
                        if (kitName == null) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.kit-create-usage"));
                            return true;
                        }
                        if (plugin.getKitManager().createNamedKit(kitName)) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.kit-create-success", "{kit}", kitName));
                        } else {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.kit-create-exists", "{kit}", kitName));
                        }
                    }
                    case "save" -> {
                        if (!(sender instanceof Player admin)) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("admin.kit-save-only-player"));
                            return true;
                        }
                        if (args.length < 3) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.kit-save-usage"));
                            return true;
                        }
                        String rawName = String.join("_", java.util.Arrays.copyOfRange(args, 2, args.length));
                        String kitName = com.scorepvp.manager.KitManager.sanitizeKitName(rawName);
                        if (kitName == null) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.kit-save-usage"));
                            return true;
                        }
                        if (plugin.getKitManager().saveNamedKit(admin, kitName)) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.kit-save-success", "{kit}", kitName));
                        } else {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.kit-save-error", "{kit}", kitName));
                        }
                    }
                    case "set" -> {
                        if (args.length < 3 || !args[2].equalsIgnoreCase("vanilla")) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.kit-set-usage"));
                            return true;
                        }
                        if (!(sender instanceof Player admin)) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("admin.kit-set-only-player"));
                            return true;
                        }
                        if (plugin.getKitManager().setVanillaDefault(admin)) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.kit-set-success"));
                        } else {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.kit-set-error"));
                        }
                    }
                    default -> sendHelp(sender);
                }
            }

            // ── Section 7: Give Custom Item ──────────────────────────────────

            case "giveitem" -> {
                // /spvp giveitem <player> <item_id>
                if (args.length < 3) {
                    sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                            + " " + plugin.getConfigManager().rawMsg("admin.giveitem-usage"));
                    sender.sendMessage(plugin.getConfigManager().rawMsg("admin.giveitem-available",
                            "{items}", String.join(", ", CustomItemRegistry.getRegisteredIds())));
                    return true;
                }
                String targetName = args[1];
                String itemId = args[2].toLowerCase();

                Player target = Bukkit.getPlayerExact(targetName);
                if (target == null) {
                    sender.sendMessage(plugin.getConfigManager().msg("player-not-found", "{player}", targetName));
                    return true;
                }

                ItemStack customItem = plugin.getCustomItemRegistry().buildItem(itemId);
                if (customItem == null) {
                    sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                            + " " + plugin.getConfigManager().rawMsg("admin.giveitem-unknown", "{item}", itemId));
                    sender.sendMessage(plugin.getConfigManager().rawMsg("admin.giveitem-available-short",
                            "{items}", String.join(", ", CustomItemRegistry.getRegisteredIds())));
                    return true;
                }

                target.getInventory().addItem(customItem);
                sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                        + " " + plugin.getConfigManager().rawMsg("admin.giveitem-sent", "{item}", itemId, "{player}", target.getName()));
                target.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                        + " " + plugin.getConfigManager().rawMsg("admin.giveitem-received", "{item}", itemId));
            }

            // ── Section 8: Clear Stats ──────────────────────────────────

            case "clear" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                            + " " + plugin.getConfigManager().rawMsg("admin.clear-usage"));
                    return true;
                }
                String clearTarget = args[1];
                if (clearTarget.equalsIgnoreCase("all")) {
                    // /spvp clear all — clear ALL stats, tiers, playtime
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        plugin.getStorage().getMain().clearAllStats();
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.getStatsManager().clearAllCache();
                            plugin.getTierManager().clearAllCache();
                            plugin.getPlaceholderCache().refreshAll();
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.clear-all-done"));
                        });
                    });
                } else {
                    // /spvp clear <player_name> — clear specific player's stats
                    Player target = Bukkit.getPlayerExact(clearTarget);
                    org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(clearTarget);
                    UUID targetUuid = target != null ? target.getUniqueId() : offline.getUniqueId();
                    if (targetUuid == null) {
                        sender.sendMessage(plugin.getConfigManager().msg("player-not-found", "{player}", clearTarget));
                        return true;
                    }
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        plugin.getStorage().getMain().clearPlayerStats(targetUuid);
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.getStatsManager().clearPlayerCache(targetUuid);
                            plugin.getTierManager().clearPlayerCache(targetUuid);
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.clear-player-done",
                                    "{player}", clearTarget));
                        });
                    });
                }
            }

            // ── Dynamic Menus ─────────────────────────────────────────────

            case "menu" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                            + " " + plugin.getConfigManager().rawMsg("admin.menu-usage"));
                    return true;
                }
                String menuSub = args[1].toLowerCase();
                switch (menuSub) {
                    case "create" -> {
                        if (args.length < 3) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.menu-create-usage"));
                            return true;
                        }
                        String menuName = args[2].toLowerCase();
                        if (plugin.getMenuManager().createMenu(menuName)) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.menu-create-success", "{menu}", menuName));
                        } else {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.menu-create-exists", "{menu}", menuName));
                        }
                    }
                    case "editor" -> {
                        if (!(sender instanceof Player admin)) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("admin.menu-editor-only-player"));
                            return true;
                        }
                        if (args.length < 3) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.menu-editor-usage"));
                            return true;
                        }
                        String menuName = args[2].toLowerCase();
                        if (plugin.getMenuManager().getMenu(menuName) == null) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.menu-not-found", "{menu}", menuName));
                            return true;
                        }
                        plugin.getMenuEditorListener().startEditing(admin.getUniqueId(), menuName);
                        plugin.getMenuManager().openEditor(admin, menuName);
                    }
                    case "open" -> {
                        if (args.length < 4) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.menu-open-usage"));
                            return true;
                        }
                        String menuName = args[2].toLowerCase();
                        String targetName = args[3];
                        Player target = Bukkit.getPlayerExact(targetName);
                        if (target == null) {
                            sender.sendMessage(plugin.getConfigManager().msg("player-not-found", "{player}", targetName));
                            return true;
                        }
                        if (!plugin.getMenuManager().openForPlayer(target, menuName)) {
                            sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                    + " " + plugin.getConfigManager().rawMsg("admin.menu-not-found", "{menu}", menuName));
                            return true;
                        }
                        sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                                + " " + plugin.getConfigManager().rawMsg("admin.menu-opened", "{menu}", menuName, "{player}", target.getName()));
                    }
                    default -> sender.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                            + " " + plugin.getConfigManager().rawMsg("admin.menu-usage"));
                }
            }

            default -> sendHelp(sender);
        }

        return true;
    }

    private void sendHelp(CommandSender s) {
        String prefix = plugin.getConfigManager().rawMsg("prefix");
        s.sendMessage(com.scorepvp.util.ColorUtil.color(prefix + " " + plugin.getConfigManager().rawStr("admin.help-title")));
        for (String line : plugin.getConfigManager().rawList("admin.help")) {
            s.sendMessage(com.scorepvp.util.ColorUtil.color(line));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("scorepvp.admin")) return List.of();
        if (args.length == 1)
            return List.of("reload","version","setspawn","settpspawn","setregion","editor","kit","giveitem","menu","clear")
                    .stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
            List<String> opts = new java.util.ArrayList<>();
            opts.add("all");
            Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
            return opts.stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("menu"))
            return List.of("create","editor","open").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("menu"))
            return plugin.getMenuManager().getMenuNames().stream()
                    .filter(s -> s.startsWith(args[2].toLowerCase())).collect(Collectors.toList());
        if (args.length == 4 && args[0].equalsIgnoreCase("menu") && args[1].equalsIgnoreCase("open"))
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[3].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 2 && args[0].equalsIgnoreCase("kit"))
            return List.of("create","save","set").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("kit") && args[1].equalsIgnoreCase("set"))
            return List.of("vanilla");
        if (args.length == 2 && args[0].equalsIgnoreCase("giveitem"))
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("giveitem"))
            return CustomItemRegistry.getRegisteredIds().stream()
                    .filter(id -> id.startsWith(args[2].toLowerCase())).collect(Collectors.toList());
        return List.of();
    }
}
