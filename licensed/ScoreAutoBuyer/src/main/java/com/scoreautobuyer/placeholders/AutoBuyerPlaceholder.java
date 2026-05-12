package com.scoreautobuyer.placeholders;

import com.scoreautobuyer.ScoreAutoBuyer;
import com.scoreautobuyer.data.PlayerData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public class AutoBuyerPlaceholder extends PlaceholderExpansion {
   private final ScoreAutoBuyer plugin;

   public AutoBuyerPlaceholder(ScoreAutoBuyer plugin) {
      this.plugin = plugin;
   }

   public String getIdentifier() {
      return "scoreautobuyer";
   }

   public String getAuthor() {
      return this.plugin.getDescription().getAuthors().toString();
   }

   public String getVersion() {
      return this.plugin.getDescription().getVersion();
   }

   public boolean persist() {
      return true;
   }

   public String onPlaceholderRequest(Player player, String params) {
      if (player == null) {
         return null;
      } else if (params.startsWith("status_")) {
         String itemKey = params.substring(7);
         if (!this.plugin.getConfigManager().getConfiguredItems().containsKey(itemKey)) {
            return "unknown";
         } else {
            PlayerData playerData = this.plugin.getConfigManager().getPlayerData(player.getUniqueId());
            return playerData.isAutoBuyerEnabled() && playerData.isItemEnabled(itemKey) ? "yes" : "no";
         }
      } else if (params.equals("autobuyer_status")) {
         PlayerData playerData = this.plugin.getConfigManager().getPlayerData(player.getUniqueId());
         return playerData.isAutoBuyerEnabled() ? "enabled" : "disabled";
      } else {
         return null;
      }
   }
}
