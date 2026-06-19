package uz.score.scorecheck;

import org.bukkit.plugin.java.JavaPlugin;
import uz.score.scorecheck.commands.CheckCommand;
import uz.score.scorecheck.listeners.PlayerCheckListener;
import uz.score.scorecheck.logger.CheckLogger;
import uz.score.scorecheck.managers.CheckManager;
import uz.score.scorecheck.managers.ConfigManager;

public final class ScoreCheck extends JavaPlugin {

    private static ScoreCheck instance;

    private ConfigManager configManager;
    private CheckLogger   checkLogger;
    private CheckManager  checkManager;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        this.configManager = new ConfigManager(this);
        this.checkLogger   = new CheckLogger(this);
        this.checkManager  = new CheckManager(this, checkLogger);

        CheckCommand checkCommand = new CheckCommand(this);
        getCommand("check").setExecutor(checkCommand);
        getCommand("check").setTabCompleter(checkCommand);

        getServer().getPluginManager().registerEvents(new PlayerCheckListener(this), this);

        getLogger().info("╔════════════════════════════╗");
        getLogger().info("║  ScoreCheck v" + getDescription().getVersion() + " enabled!  ║");
        getLogger().info("║  ScoreStudio | uz.score     ║");
        getLogger().info("╚════════════════════════════╝");
    }

    @Override
    public void onDisable() {
        if (checkManager != null) {
            checkManager.onShutdown();
        }
        getLogger().info("ScoreCheck disabled. All sessions cleaned up.");
    }

    public void reload() {
        reloadConfig();
        configManager.reload();
    }

    public static ScoreCheck getInstance()       { return instance; }
    public ConfigManager     getConfigManager()  { return configManager; }
    public CheckManager      getCheckManager()   { return checkManager; }
    public CheckLogger       getCheckLogger()    { return checkLogger; }
}
