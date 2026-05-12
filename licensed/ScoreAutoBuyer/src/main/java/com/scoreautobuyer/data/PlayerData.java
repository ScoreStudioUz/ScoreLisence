package com.scoreautobuyer.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerData {
   private final UUID uuid;
   private boolean autoBuyerEnabled;
   private final Map<String, Boolean> itemSettings;

   public PlayerData(UUID uuid) {
      this.uuid = uuid;
      this.autoBuyerEnabled = false;
      this.itemSettings = new HashMap<>();
   }

   public UUID getUuid() {
      return this.uuid;
   }

   public boolean isAutoBuyerEnabled() {
      return this.autoBuyerEnabled;
   }

   public void setAutoBuyerEnabled(boolean enabled) {
      this.autoBuyerEnabled = enabled;
   }

   public boolean isItemEnabled(String itemKey) {
      return this.itemSettings.getOrDefault(itemKey, true);
   }

   public void setItemEnabled(String itemKey, boolean enabled) {
      this.itemSettings.put(itemKey, enabled);
   }

   public Map<String, Boolean> getItemSettings() {
      return new HashMap<>(this.itemSettings);
   }

   public void clearItemSettings() {
      this.itemSettings.clear();
   }
}
