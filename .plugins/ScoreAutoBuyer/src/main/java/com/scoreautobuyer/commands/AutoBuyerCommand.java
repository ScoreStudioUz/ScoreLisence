package com.scoreautobuyer.commands;

import com.scoreautobuyer.ScoreAutoBuyer;
import com.scoreautobuyer.data.PlayerData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class AutoBuyerCommand implements CommandExecutor, TabCompleter {
   private final ScoreAutoBuyer plugin;

   public AutoBuyerCommand(ScoreAutoBuyer plugin) {
      this.plugin = plugin;
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!sender.hasPermission("scoreautobuyer.admin")) {
         sender.sendMessage(this.plugin.getConfigManager().getMessage("no-permission"));
         return true;
      } else if (args.length == 0) {
         this.sendHelp(sender);
         return true;
      } else {
         switch (args[0].toLowerCase()) {
            case "enable":
               if (args.length != 2) {
                  sender.sendMessage(this.plugin.getConfigManager().getMessage("usage-enable"));
                  return true;
               }

               this.handleEnable(sender, args[1]);
               break;
            case "disable":
               if (args.length != 2) {
                  sender.sendMessage(this.plugin.getConfigManager().getMessage("usage-disable"));
                  return true;
               }

               this.handleDisable(sender, args[1]);
               break;
            case "items":
               if (args.length != 4) {
                  sender.sendMessage(this.plugin.getConfigManager().getMessage("usage-items"));
                  return true;
               }

               this.handleItems(sender, args[1], args[2], args[3]);
               break;
            case "reload":
               this.handleReload(sender);
               break;
            default:
               this.sendHelp(sender);
         }

         return true;
      }
   }

   private void handleEnable(CommandSender sender, String playerName) {
      Player target = Bukkit.getPlayer(playerName);
      if (target == null) {
         sender.sendMessage(this.plugin.getConfigManager().getMessage("player-not-found").replace("%player%", playerName));
      } else {
         PlayerData playerData = this.plugin.getConfigManager().getPlayerData(target.getUniqueId());
         playerData.setAutoBuyerEnabled(true);
         sender.sendMessage(this.plugin.getConfigManager().getMessage("autobuyer-enabled").replace("%player%", target.getName()));
         target.sendMessage(this.plugin.getConfigManager().getMessage("autobuyer-enabled-self"));
      }
   }

   private void handleDisable(CommandSender sender, String playerName) {
      Player target = Bukkit.getPlayer(playerName);
      if (target == null) {
         sender.sendMessage(this.plugin.getConfigManager().getMessage("player-not-found").replace("%player%", playerName));
      } else {
         PlayerData playerData = this.plugin.getConfigManager().getPlayerData(target.getUniqueId());
         playerData.setAutoBuyerEnabled(false);
         sender.sendMessage(this.plugin.getConfigManager().getMessage("autobuyer-disabled").replace("%player%", target.getName()));
         target.sendMessage(this.plugin.getConfigManager().getMessage("autobuyer-disabled-self"));
      }
   }

   private void handleItems(CommandSender sender, String action, String itemName, String playerName) {
      Player target = Bukkit.getPlayer(playerName);
      if (target == null) {
         sender.sendMessage(this.plugin.getConfigManager().getMessage("player-not-found").replace("%player%", playerName));
      } else if (!this.plugin.getConfigManager().getConfiguredItems().containsKey(itemName)) {
         sender.sendMessage(this.plugin.getConfigManager().getMessage("item-not-configured").replace("%item%", itemName));
      } else {
         PlayerData playerData = this.plugin.getConfigManager().getPlayerData(target.getUniqueId());
         boolean enable = action.equalsIgnoreCase("enable");
         playerData.setItemEnabled(itemName, enable);
         String messageKey = enable ? "item-enabled" : "item-disabled";
         sender.sendMessage(this.plugin.getConfigManager().getMessage(messageKey).replace("%item%", itemName).replace("%player%", target.getName()));
         target.sendMessage(this.plugin.getConfigManager().getMessage(messageKey + "-self").replace("%item%", itemName));
      }
   }

   private void handleReload(CommandSender sender) {
      this.plugin.getConfigManager().loadConfigs();
      sender.sendMessage(this.plugin.getConfigManager().getMessage("config-reloaded"));
   }

   private void sendHelp(CommandSender sender) {
      sender.sendMessage("§6=== ScoreAutoBuyer Команды ===");
      sender.sendMessage("§e/scoreautobuyer enable <игрок> §7- Включить автопокупатель");
      sender.sendMessage("§e/scoreautobuyer disable <игрок> §7- Отключить автопокупатель");
      sender.sendMessage("§e/scoreautobuyer items <enable/disable> <предмет> <игрок> §7- Управление предметами");
      sender.sendMessage("§e/scoreautobuyer reload §7- Перезагрузить конфигурацию");
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!sender.hasPermission("scoreautobuyer.admin")) {
         return new ArrayList<>();
      } else {
         List<String> completions = new ArrayList<>();
         if (args.length == 1) {
            completions.addAll(Arrays.asList("enable", "disable", "items", "reload"));
         } else if (args.length == 2) {
            if (!args[0].equalsIgnoreCase("enable") && !args[0].equalsIgnoreCase("disable")) {
               if (args[0].equalsIgnoreCase("items")) {
                  completions.addAll(Arrays.asList("enable", "disable"));
               }
            } else {
               for(Player player : Bukkit.getOnlinePlayers()) {
                  completions.add(player.getName());
               }
            }
         } else if (args.length == 3 && args[0].equalsIgnoreCase("items")) {
            completions.addAll(this.plugin.getConfigManager().getConfiguredItems().keySet());
         } else if (args.length == 4 && args[0].equalsIgnoreCase("items")) {
            for(Player player : Bukkit.getOnlinePlayers()) {
               completions.add(player.getName());
            }
         }

         return completions;
      }
   }
}
