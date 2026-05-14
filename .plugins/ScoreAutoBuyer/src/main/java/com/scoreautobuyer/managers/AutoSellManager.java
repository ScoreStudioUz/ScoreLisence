package com.scoreautobuyer.managers;

import com.scoreautobuyer.ScoreAutoBuyer;
import com.scoreautobuyer.data.PlayerData;
import com.scoreautobuyer.utils.MathEvaluator;
import java.util.logging.Logger;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class AutoSellManager {
   private final ScoreAutoBuyer plugin;
   private BukkitTask autoSellTask;

   public AutoSellManager(ScoreAutoBuyer plugin) {
      this.plugin = plugin;
   }

   public void startAutoSellTask() {
      if (this.autoSellTask != null) {
         this.autoSellTask.cancel();
      }

      int interval = this.plugin.getConfigManager().getCheckInterval();
      this.autoSellTask = (new BukkitRunnable() {
         public void run() {
            AutoSellManager.this.processAllPlayers();
         }
      }).runTaskTimer(this.plugin, (long)interval, (long)interval);
      this.plugin.getLogger().info("Auto-sell задача запущена с интервалом " + interval + " тиков");
   }

   public void stopAutoSellTask() {
      if (this.autoSellTask != null) {
         this.autoSellTask.cancel();
         this.autoSellTask = null;
      }

   }

   private void processAllPlayers() {
      for(Player player : Bukkit.getOnlinePlayers()) {
         PlayerData playerData = this.plugin.getConfigManager().getPlayerData(player.getUniqueId());
         if (playerData.isAutoBuyerEnabled()) {
            this.processPlayerInventory(player, playerData);
         }
      }

   }

   private void processPlayerInventory(Player player, PlayerData playerData) {
      for(String itemKey : this.plugin.getConfigManager().getConfiguredItems().keySet()) {
         if (playerData.isItemEnabled(itemKey)) {
            ConfigManager.ItemConfig itemConfig = (ConfigManager.ItemConfig)this.plugin.getConfigManager().getConfiguredItems().get(itemKey);
            this.processItem(player, itemKey, itemConfig);
         }
      }

   }

   private void processItem(Player player, String itemKey, ConfigManager.ItemConfig itemConfig) {
      int itemCount = 0;

      for(ItemStack item : player.getInventory().getContents()) {
         if (item != null && item.getType() == itemConfig.getMaterial()) {
            itemCount += item.getAmount();
         }
      }

      if (itemCount != 0) {
         double totalPrice = this.calculatePrice(player, itemConfig.getPrice(), itemCount);
         if (!(totalPrice <= (double)0.0F)) {
            this.removeItemsFromInventory(player, itemConfig.getMaterial(), itemCount);
            this.plugin.getEconomyManager().depositPlayer(player, totalPrice);
            player.sendMessage(this.plugin.getConfigManager().getMessage("item-sold").replace("%item%", itemConfig.getName()).replace("%amount%", String.valueOf(itemCount)).replace("%price%", this.plugin.getEconomyManager().format(totalPrice)));
         }
      }
   }

   private double calculatePrice(Player player, String priceExpression, int itemCount) {
      try {
         String processedExpression = priceExpression;
         if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            processedExpression = PlaceholderAPI.setPlaceholders(player, priceExpression);
         }

         processedExpression = processedExpression.replace("%amount%", String.valueOf(itemCount));
         return MathEvaluator.evaluate(processedExpression);
      } catch (Exception e) {
         Logger var10000 = this.plugin.getLogger();
         String var10001 = player.getName();
         var10000.warning("Ошибка при расчете цены для игрока " + var10001 + ": " + e.getMessage() + " (выражение: " + priceExpression + ")");
         return (double)0.0F;
      }
   }

   private void removeItemsFromInventory(Player player, Material material, int amount) {
      int remaining = amount;

      for(int i = 0; i < player.getInventory().getSize() && remaining > 0; ++i) {
         ItemStack item = player.getInventory().getItem(i);
         if (item != null && item.getType() == material) {
            int itemAmount = item.getAmount();
            if (itemAmount <= remaining) {
               player.getInventory().setItem(i, (ItemStack)null);
               remaining -= itemAmount;
            } else {
               item.setAmount(itemAmount - remaining);
               remaining = 0;
            }
         }
      }

      player.updateInventory();
   }
}
