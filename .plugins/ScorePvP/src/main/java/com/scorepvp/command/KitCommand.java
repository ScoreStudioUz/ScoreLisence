package com.scorepvp.command;

import com.scorepvp.ScorePvP;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /kit save  — save personal kit (must be in vanilla region)
 * /kit reset — reset to default vanilla kit (BLOCKED in vanilla region)
 */
public class KitCommand implements CommandExecutor, TabCompleter {

    private final ScorePvP plugin;

    public KitCommand(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().rawMsg("players-only"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(plugin.getConfigManager().msg("invalid-usage",
                    "{usage}", "/kit <save|reset>"));
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "save" -> {
                // Must be inside vanilla chest region
                if (!plugin.getConfigManager().isInChestRegion(player.getLocation())) {
                    player.sendMessage(plugin.getConfigManager().msg("kit.not-in-region"));
                    return true;
                }
                plugin.getKitManager().savePersonalKit(player);
                player.sendMessage(plugin.getConfigManager().msg("kit.saved"));

                // Mark as leaving region before teleport
                plugin.getPlayerDataManager().setInVanillaRegion(player.getUniqueId(), false);

                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    player.getInventory().clear();
                    player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
                    player.getInventory().setItemInOffHand(null);

                    Location spawn = plugin.getConfigManager().getTpSpawnLocation();
                    if (spawn == null) spawn = plugin.getServer().getWorlds().get(0).getSpawnLocation();
                    player.teleport(spawn);

                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        plugin.getHotbarManager().restoreHotbar(player);
                    }, 1L);

                    player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                            + " " + plugin.getConfigManager().rawMsg("kit.kit-saved-spawn"));
                }, 2L);
            }

            case "reset" -> {
                // Section 6: strictly forbidden inside vanilla region
                if (plugin.getConfigManager().isInChestRegion(player.getLocation())) {
                    player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                            + " " + plugin.getConfigManager().rawMsg("kit.kit-reset-blocked"));
                    return true;
                }
                // Section 2: data-only reset — clears personal kit, gives default on next region entry
                plugin.getKitManager().resetPersonalKit(player);
            }

            default -> player.sendMessage(plugin.getConfigManager().msg("invalid-usage",
                    "{usage}", "/kit <save|reset>"));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) return List.of("save", "reset");
        return List.of();
    }
}
