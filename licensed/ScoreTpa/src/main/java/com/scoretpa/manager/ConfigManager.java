package com.scoretpa.manager;

import com.scoretpa.ScoreTpa;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConfigManager {

    private final ScoreTpa plugin;
    private FileConfiguration messages;

    private String prefix;
    private String language;
    private int expireTime;
    private int cooldown;
    private boolean bossbarEnabled;
    private String bossbarColorName;
    private String bossbarStyleName;
    private final Map<String, Integer> groupDelays = new LinkedHashMap<>();
    private boolean cancelOnMovement;
    private boolean cancelOnCombat;
    private boolean debug;

    public ConfigManager(ScoreTpa plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.saveDefaultConfig();
        saveDefaultMessages();

        FileConfiguration config = plugin.getConfig();

        prefix = config.getString("settings.prefix", "&8[&bScoreTpa&8] ");
        language = config.getString("settings.language", "en");
        expireTime = config.getInt("settings.expire-time", 120);
        cooldown = config.getInt("settings.cooldown", 10);

        bossbarEnabled = config.getBoolean("settings.bossbar.enabled", true);
        bossbarColorName = config.getString("settings.bossbar.color", "BLUE");
        bossbarStyleName = config.getString("settings.bossbar.style", "SOLID");

        groupDelays.clear();
        if (config.isConfigurationSection("group-delays")) {
            for (String group : config.getConfigurationSection("group-delays").getKeys(false)) {
                groupDelays.put(group, config.getInt("group-delays." + group));
            }
        }

        cancelOnMovement = config.getBoolean("cancellation.movement", false);
        cancelOnCombat = config.getBoolean("cancellation.combat", true);
        debug = config.getBoolean("debug", false);

        loadMessages();
    }

    public void reload() {
        plugin.reloadConfig();
        load();
    }

    private void saveDefaultMessages() {
        String[] langs = {"en", "ru", "uz"};
        for (String lang : langs) {
            File file = new File(plugin.getDataFolder(), "messages/" + lang + ".yml");
            if (!file.exists()) {
                plugin.saveResource("messages/" + lang + ".yml", false);
            }
        }
    }

    private void loadMessages() {
        File msgFile = new File(plugin.getDataFolder(), "messages/" + language + ".yml");
        if (!msgFile.exists()) {
            plugin.getLogger().warning("Language file not found: " + language + ".yml, falling back to en.yml");
            msgFile = new File(plugin.getDataFolder(), "messages/en.yml");
        }
        messages = YamlConfiguration.loadConfiguration(msgFile);

        InputStream defaultStream = plugin.getResource("messages/" + language + ".yml");
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            messages.setDefaults(defaults);
        }
    }

    public String getMessage(String key) {
        return messages.getString("messages." + key, "&c[Missing: " + key + "]");
    }

    public String getMessage(String key, String... replacements) {
        String msg = getMessage(key);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    public String getPrefix() {
        return prefix;
    }

    public int getExpireTime() {
        return expireTime;
    }

    public int getCooldown() {
        return cooldown;
    }

    public boolean isBossbarEnabled() {
        return bossbarEnabled;
    }

    public String getBossbarColorName() {
        return bossbarColorName;
    }

    public String getBossbarStyleName() {
        return bossbarStyleName;
    }

    public Map<String, Integer> getGroupDelays() {
        return groupDelays;
    }

    public boolean isCancelOnMovement() {
        return cancelOnMovement;
    }

    public boolean isCancelOnCombat() {
        return cancelOnCombat;
    }

    public boolean isDebug() {
        return debug;
    }
}
