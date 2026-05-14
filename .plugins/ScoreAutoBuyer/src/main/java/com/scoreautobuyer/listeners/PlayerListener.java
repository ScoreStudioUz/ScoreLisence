package com.scoreautobuyer.listeners;

import com.scoreautobuyer.ScoreAutoBuyer;
import com.scoreautobuyer.data.PlayerData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {
   private final ScoreAutoBuyer plugin;

   public PlayerListener(ScoreAutoBuyer plugin) {
      this.plugin = plugin;
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      PlayerData playerData = this.plugin.getConfigManager().getPlayerData(event.getPlayer().getUniqueId());
      if (playerData.isAutoBuyerEnabled()) {
         playerData.setAutoBuyerEnabled(false);
         event.getPlayer().sendMessage(this.plugin.getConfigManager().getMessage("autobuyer-reset-login"));
      }

   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent event) {
      PlayerData playerData = this.plugin.getConfigManager().getPlayerData(event.getPlayer().getUniqueId());
      if (playerData.isAutoBuyerEnabled()) {
         playerData.setAutoBuyerEnabled(false);
      }

      this.plugin.getConfigManager().savePlayerData();
   }
}
