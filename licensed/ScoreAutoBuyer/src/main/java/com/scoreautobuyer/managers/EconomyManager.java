package com.scoreautobuyer.managers;

import com.scoreautobuyer.ScoreAutoBuyer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public class EconomyManager {
   private final ScoreAutoBuyer plugin;
   private Economy economy = null;

   public EconomyManager(ScoreAutoBuyer plugin) {
      this.plugin = plugin;
   }

   public boolean setupEconomy() {
      if (this.plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
         return false;
      } else {
         RegisteredServiceProvider<Economy> rsp = this.plugin.getServer().getServicesManager().getRegistration(Economy.class);
         if (rsp == null) {
            return false;
         } else {
            this.economy = (Economy)rsp.getProvider();
            return this.economy != null;
         }
      }
   }

   public Economy getEconomy() {
      return this.economy;
   }

   public boolean hasEconomy() {
      return this.economy != null;
   }

   public boolean hasBalance(Player player, double amount) {
      return !this.hasEconomy() ? false : this.economy.has(player, amount);
   }

   public void depositPlayer(Player player, double amount) {
      if (this.hasEconomy()) {
         this.economy.depositPlayer(player, amount);
      }
   }

   public void withdrawPlayer(Player player, double amount) {
      if (this.hasEconomy()) {
         this.economy.withdrawPlayer(player, amount);
      }
   }

   public double getBalance(Player player) {
      return !this.hasEconomy() ? (double)0.0F : this.economy.getBalance(player);
   }

   public String format(double amount) {
      return !this.hasEconomy() ? String.valueOf(amount) : this.economy.format(amount);
   }
}
