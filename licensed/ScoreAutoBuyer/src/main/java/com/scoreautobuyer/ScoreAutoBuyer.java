package com.scoreautobuyer;

import com.scoreautobuyer.commands.AutoBuyerCommand;
import com.scoreautobuyer.listeners.PlayerListener;
import com.scoreautobuyer.managers.AutoSellManager;
import com.scoreautobuyer.managers.ConfigManager;
import com.scoreautobuyer.managers.EconomyManager;
import com.scoreautobuyer.placeholders.AutoBuyerPlaceholder;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class ScoreAutoBuyer extends JavaPlugin {
   private static ScoreAutoBuyer instance;
   private ConfigManager configManager;
   private EconomyManager economyManager;
   private AutoSellManager autoSellManager;
   private AutoBuyerPlaceholder placeholderExpansion;

   @Override
   public void onEnable() {
      instance = this;

      this.configManager = new ConfigManager(this);
      this.economyManager = new EconomyManager(this);
      this.autoSellManager = new AutoSellManager(this);
      this.configManager.loadConfigs();
      if (!this.economyManager.setupEconomy()) {
         this.getLogger().severe("Vault не найден! Плагин будет отключен.");
         this.getServer().getPluginManager().disablePlugin(this);
         return;
      }
      this.getCommand("scoreautobuyer").setExecutor(new AutoBuyerCommand(this));
      this.getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
      if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
         this.placeholderExpansion = new AutoBuyerPlaceholder(this);
         this.placeholderExpansion.register();
         this.getLogger().info("PlaceholderAPI интеграция включена!");
      } else {
         this.getLogger().warning("PlaceholderAPI не найден! Плейсхолдеры недоступны.");
      }

      this.autoSellManager.startAutoSellTask();
      this.getLogger().info("ScoreAutoBuyer успешно включен!");
   }

   @Override
   public void onDisable() {
      if (this.autoSellManager != null) {
         this.autoSellManager.stopAutoSellTask();
      }

      if (this.placeholderExpansion != null) {
         this.placeholderExpansion.unregister();
      }

      if (this.configManager != null) {
         this.configManager.savePlayerData();
      }

      this.getLogger().info("ScoreAutoBuyer отключен!");
   }

   public static ScoreAutoBuyer getInstance() {
      return instance;
   }

   public ConfigManager getConfigManager() {
      return this.configManager;
   }

   public EconomyManager getEconomyManager() {
      return this.economyManager;
   }

   public AutoSellManager getAutoSellManager() {
      return this.autoSellManager;
   }
}
