package com.scoreautobuyer.managers;

import com.scoreautobuyer.ScoreAutoBuyer;
import com.scoreautobuyer.data.PlayerData;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class ConfigManager {
   private final ScoreAutoBuyer plugin;
   private FileConfiguration config;
   private FileConfiguration messages;
   private FileConfiguration playerDataConfig;
   private File playerDataFile;
   private Map<String, ItemConfig> configuredItems = new HashMap<>();
   private Map<UUID, PlayerData> playerDataMap = new HashMap<>();

   public ConfigManager(ScoreAutoBuyer plugin) {
      this.plugin = plugin;
   }

   public void loadConfigs() {
      this.plugin.saveDefaultConfig();
      this.plugin.reloadConfig();
      this.saveResource("messages.yml");
      this.saveResource("playerdata.yml");
      this.config = this.plugin.getConfig();
      this.messages = YamlConfiguration.loadConfiguration(new File(this.plugin.getDataFolder(), "messages.yml"));
      this.playerDataFile = new File(this.plugin.getDataFolder(), "playerdata.yml");
      this.playerDataConfig = YamlConfiguration.loadConfiguration(this.playerDataFile);
      this.loadItems();
      this.loadPlayerData();
   }

   private void saveResource(String resourcePath) {
      File file = new File(this.plugin.getDataFolder(), resourcePath);
      if (!file.exists()) {
         this.plugin.saveResource(resourcePath, false);
      }
   }

   private void loadItems() {
      this.configuredItems.clear();
      if (this.config.getConfigurationSection("items") == null) {
         this.plugin.getLogger().warning("Секция 'items' не найдена в config.yml!");
         return;
      }
      for (String itemKey : this.config.getConfigurationSection("items").getKeys(false)) {
         String path = "items." + itemKey;
         String name = this.config.getString(path + ".name");
         String materialName = this.config.getString(path + ".material");
         String price = this.config.getString(path + ".price");
         if (name != null && materialName != null && price != null) {
            try {
               Material material = Material.valueOf(materialName.toUpperCase());
               this.configuredItems.put(itemKey, new ItemConfig(name, material, price));
               this.plugin.getLogger().info("Загружен предмет: " + itemKey + " (" + name + ")");
            } catch (IllegalArgumentException e) {
               this.plugin.getLogger().warning("Неизвестный материал: " + materialName + " для предмета: " + itemKey);
            }
         } else {
            this.plugin.getLogger().warning("Неполная конфигурация для предмета: " + itemKey);
         }
      }
      this.plugin.getLogger().info("Загружено " + this.configuredItems.size() + " предметов из конфигурации");
   }

   private void loadPlayerData() {
      this.playerDataMap.clear();
      if (this.playerDataConfig.getConfigurationSection("players") == null) {
         return;
      }
      for (String uuidString : this.playerDataConfig.getConfigurationSection("players").getKeys(false)) {
         try {
            UUID uuid = UUID.fromString(uuidString);
            PlayerData playerData = new PlayerData(uuid);
            String path = "players." + uuidString;
            if (this.playerDataConfig.getConfigurationSection(path + ".items") != null) {
               for (String itemKey : this.playerDataConfig.getConfigurationSection(path + ".items").getKeys(false)) {
                  boolean enabled = this.playerDataConfig.getBoolean(path + ".items." + itemKey);
                  playerData.setItemEnabled(itemKey, enabled);
               }
            }
            this.playerDataMap.put(uuid, playerData);
         } catch (IllegalArgumentException e) {
            this.plugin.getLogger().warning("Неверный UUID в playerdata.yml: " + uuidString);
         }
      }
      this.plugin.getLogger().info("Загружены данные для " + this.playerDataMap.size() + " игроков");
   }

   public void savePlayerData() {
      for (Map.Entry<UUID, PlayerData> entry : this.playerDataMap.entrySet()) {
         UUID uuid = entry.getKey();
         PlayerData playerData = entry.getValue();
         String path = "players." + uuid.toString();
         for (Map.Entry<String, Boolean> itemEntry : playerData.getItemSettings().entrySet()) {
            this.playerDataConfig.set(path + ".items." + itemEntry.getKey(), itemEntry.getValue());
         }
      }
      try {
         this.playerDataConfig.save(this.playerDataFile);
      } catch (IOException e) {
         this.plugin.getLogger().severe("Не удалось сохранить playerdata.yml: " + e.getMessage());
      }
   }

   public PlayerData getPlayerData(UUID uuid) {
      return this.playerDataMap.computeIfAbsent(uuid, PlayerData::new);
   }

   public String getMessage(String key) {
      return this.messages.getString("messages." + key, "§cСообщение не найдено: " + key).replace("&", "§");
   }

   public Map<String, ItemConfig> getConfiguredItems() {
      return new HashMap<>(this.configuredItems);
   }

   public int getCheckInterval() {
      return this.config.getInt("check-interval", 20);
   }

   public static class ItemConfig {
      private final String name;
      private final Material material;
      private final String price;

      public ItemConfig(String name, Material material, String price) {
         this.name = name;
         this.material = material;
         this.price = price;
      }

      public String getName() {
         return this.name;
      }

      public Material getMaterial() {
         return this.material;
      }

      public String getPrice() {
         return this.price;
      }
   }
}
