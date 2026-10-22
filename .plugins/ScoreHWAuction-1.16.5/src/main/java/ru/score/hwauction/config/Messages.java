package ru.score.hwauction.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.util.ColorUtil;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

public final class Messages {

    private final ScoreHWAuction plugin;
    private FileConfiguration cfg;

    public Messages(ScoreHWAuction plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        cfg = YamlConfiguration.loadConfiguration(file);
    }

    /** Get with prefix */
    public String get(String path) {
        String val = cfg.getString(path);
        if (val == null) {
            plugin.getLogger().warning("Missing message key: " + path);
            return ColorUtil.translate("&c[MSG:" + path + "]");
        }
        String prefix = ColorUtil.translate(cfg.getString("prefix", "&8[&bАукцион&8] &r"));
        return ColorUtil.translate(prefix + val);
    }

    /** Get with prefix + placeholder substitution */
    public String get(String path, String... pairs) {
        String msg = get(path);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            msg = msg.replace(pairs[i], pairs[i + 1]);
        }
        return msg;
    }

    /** Get without prefix */
    public String raw(String path) {
        String val = cfg.getString(path);
        if (val == null) {
            plugin.getLogger().warning("Missing message key: " + path);
            return ColorUtil.translate("&c[MSG:" + path + "]");
        }
        return ColorUtil.translate(val);
    }

    /** Get without prefix + placeholder substitution */
    public String raw(String path, String... pairs) {
        String msg = raw(path);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            msg = msg.replace(pairs[i], pairs[i + 1]);
        }
        return msg;
    }

    /** Get string list translated */
    public List<String> list(String path) {
        return cfg.getStringList(path).stream()
                .map(ColorUtil::translate)
                .collect(Collectors.toList());
    }

    public FileConfiguration raw() { return cfg; }
}
